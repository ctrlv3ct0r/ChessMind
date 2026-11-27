package com.chessmind.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.random.Random

private val forest = Color(0xFF080B18)
private val panel = Color(0xFF171D30)
private val cream = Color(0xFFF7F3EA)
private val green = Color(0xFFFFC84A)
private val darkSquare = Color(0xFFA95E35)
private val lightSquare = Color(0xFFF4D18A)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ChessMindApp(this) }
    }
}

private data class PlayerProfile(
    val rating: Int = 650,
    val games: Int = 0,
    val wins: Int = 0,
    val tactical: Int = 0,
    val aggressive: Int = 0,
    val kingSafety: Int = 0,
    val blunders: Int = 0
)

private class AdaptiveCoach(private val context: Context) {
    private val prefs = context.getSharedPreferences("chessmind_profile", Context.MODE_PRIVATE)
    fun load() = PlayerProfile(
        rating = prefs.getInt("rating", 650), games = prefs.getInt("games", 0),
        wins = prefs.getInt("wins", 0), tactical = prefs.getInt("tactical", 0),
        aggressive = prefs.getInt("aggressive", 0), kingSafety = prefs.getInt("kingSafety", 0),
        blunders = prefs.getInt("blunders", 0)
    )
    fun save(p: PlayerProfile) {
        prefs.edit().putInt("rating", p.rating).putInt("games", p.games).putInt("wins", p.wins)
            .putInt("tactical", p.tactical).putInt("aggressive", p.aggressive)
            .putInt("kingSafety", p.kingSafety).putInt("blunders", p.blunders).apply()
    }
    fun observeMove(profile: PlayerProfile, before: Board, move: Move, after: Board): PlayerProfile {
        val capture = before.getPiece(move.to).toString() != "NONE"
        val to = move.to.toString()
        val pawnPush = before.getPiece(move.from).toString().contains("PAWN")
        val center = to in setOf("D4", "E4", "D5", "E5", "C4", "F4", "C5", "F5")
        return profile.copy(
            tactical = (profile.tactical + if (capture) 1 else 0).coerceAtMost(1000),
            aggressive = (profile.aggressive + if (pawnPush && center) 1 else 0).coerceAtMost(1000)
        )
    }
    fun finish(profile: PlayerProfile, won: Boolean, blundersThisGame: Int): PlayerProfile {
        val delta = when { won -> 18; blundersThisGame >= 3 -> -8; else -> 5 }
        val next = profile.copy(rating = (profile.rating + delta).coerceIn(400, 1800),
            games = profile.games + 1, wins = profile.wins + if (won) 1 else 0,
            blunders = profile.blunders + blundersThisGame)
        save(next); return next
    }
    fun targetDepth(p: PlayerProfile) = when {
        p.rating < 750 -> 1
        p.rating < 1000 -> 2
        p.rating < 1300 -> 3
        else -> 4
    }
}

private data class ScoredMove(val move: Move, val score: Double)

/** A compact adaptive search layer. Rules and legal move generation are delegated to chesslib. */
private class AdaptiveEngine(private val coach: AdaptiveCoach) {
    fun choose(board: Board, profile: PlayerProfile): Move {
        val legal = board.legalMoves()
        require(legal.isNotEmpty())
        val depth = coach.targetDepth(profile)
        val candidates = legal.map { move ->
            val child = Board().apply { loadFromFen(board.fen); doMove(move) }
            val immediate = evaluate(child, profile)
            val replies = child.legalMoves()
            val replyPenalty = if (replies.isEmpty()) immediate else replies.minOf { reply ->
                val grandchild = Board().apply { loadFromFen(child.fen); doMove(reply) }
                evaluate(grandchild, profile)
            }
            val minimax = if (depth <= 1) immediate else immediate * 0.55 + replyPenalty * 0.45
            ScoredMove(move, minimax + styleBias(board, move, profile))
        }.sortedByDescending { it.score }
        val choicePool = when {
            profile.rating < 750 -> candidates.take(minOf(5, candidates.size))
            profile.rating < 1100 -> candidates.take(minOf(3, candidates.size))
            else -> candidates.take(minOf(2, candidates.size))
        }
        val weights = choicePool.mapIndexed { i, _ -> (choicePool.size - i).toDouble().pow2() }
        val pick = weightedIndex(weights)
        return choicePool[pick].move
    }
    fun bestMove(board: Board, depth: Int = 3): Move? {
        val legal = board.legalMoves()
        if (legal.isEmpty()) return null
        var best: Move? = null
        var bestScore = Double.NEGATIVE_INFINITY
        var alpha = Double.NEGATIVE_INFINITY
        for (move in legal) {
            val child = Board().apply { loadFromFen(board.fen); doMove(move) }
            val score = -negamax(child, depth - 1, -1000000.0, -alpha)
            if (score > bestScore) { bestScore = score; best = move }
            alpha = maxOf(alpha, score)
        }
        return best
    }
    fun analyzeMove(beforeFen: String, from: Square, to: Square, depth: Int): String {
        val before = Board().apply { loadFromFen(beforeFen) }
        val played = before.legalMoves().firstOrNull { it.from == from && it.to == to }
            ?: return "Не удалось восстановить ход для анализа."
        val best = bestMove(before, depth)
        val actualBoard = Board().apply { loadFromFen(beforeFen); doMove(played) }
        val actualScore = -negamax(actualBoard, depth - 1, -1000000.0, 1000000.0)
        val bestBoard = best?.let { m -> Board().apply { loadFromFen(beforeFen); doMove(m) } }
        val bestScore = bestBoard?.let { -negamax(it, depth - 1, -1000000.0, 1000000.0) } ?: actualScore
        val loss = (bestScore - actualScore).coerceAtLeast(0.0)
        val quality = when {
            loss < 35 -> "Отличный ход: практически лучший в позиции."
            loss < 100 -> "Хороший ход: небольшая неточность."
            loss < 250 -> "Неточность: был вариант сильнее."
            else -> "Серьёзная ошибка: ход заметно ухудшает позицию."
        }
        val bestText = best?.let { "${it.from.name.lowercase()} → ${it.to.name.lowercase()}" } ?: "—"
        return "Оценка хода: ${quality}\nПотеря относительно лучшего варианта: ${loss.toInt()} сотых пешки.\nЛучший вариант: $bestText"
    }
    private fun negamax(board: Board, depth: Int, alphaStart: Double, beta: Double): Double {
        if (board.isMated) return -100000.0 - depth
        if (board.isDraw) return 0.0
        if (depth <= 0) return evaluateForTurn(board)
        var alpha = alphaStart
        var best = Double.NEGATIVE_INFINITY
        for (move in board.legalMoves()) {
            val child = Board().apply { loadFromFen(board.fen); doMove(move) }
            val score = -negamax(child, depth - 1, -beta, -alpha)
            best = maxOf(best, score)
            alpha = maxOf(alpha, score)
            if (alpha >= beta) break
        }
        return best
    }
    private fun evaluateForTurn(board: Board): Double {
        val fen = board.fen.split(" ")
        val values = mapOf('p' to 100, 'n' to 320, 'b' to 335, 'r' to 500, 'q' to 900, 'k' to 0)
        var whiteScore = 0.0
        fen[0].split("/").forEachIndexed { row, rank ->
            var file = 0
            rank.forEach { ch ->
                if (ch.isDigit()) file += ch.digitToInt() else {
                    val sign = if (ch.isUpperCase()) 1.0 else -1.0
                    val value = (values[ch.lowercaseChar()] ?: 0).toDouble()
                    val center = 7.0 - (abs(3.5 - file) + abs(3.5 - row))
                    val positional = if (ch.lowercaseChar() == 'n' || ch.lowercaseChar() == 'b') center * 2.0 else 0.0
                    whiteScore += sign * (value + positional)
                    file++
                }
            }
        }
        val whiteToMove = board.sideToMove.toString() == "WHITE"
        return if (whiteToMove) whiteScore else -whiteScore
    }
    private fun Double.pow2() = this * this
    private fun weightedIndex(weights: List<Double>): Int {
        val total = weights.sum(); var x = Random.nextDouble() * total
        weights.forEachIndexed { i, w -> x -= w; if (x <= 0) return i }
        return weights.lastIndex
    }
    private fun styleBias(board: Board, move: Move, p: PlayerProfile): Double {
        val piece = board.getPiece(move.from).toString()
        val capture = board.getPiece(move.to).toString() != "NONE"
        val center = move.to.toString() in setOf("D4", "E4", "D5", "E5")
        return (if (p.aggressive > p.games * 2) 0.18 else 0.0) * (if (center) 1.0 else 0.0) +
            (if (p.tactical > p.games * 2) 0.12 else 0.0) * (if (capture) 1.0 else 0.0) +
            (if (piece.contains("KING")) -0.15 else 0.0)
    }
    private fun evaluate(board: Board, p: PlayerProfile): Double {
        if (board.isMated) return if (board.sideToMove.toString() == "WHITE") 100000.0 else -100000.0
        if (board.isDraw) return 0.0
        val fen = board.fen.split(" ")
        val values = mapOf('p' to 100, 'n' to 315, 'b' to 330, 'r' to 500, 'q' to 900, 'k' to 0)
        var score = 0.0
        val placement = fen[0].split("/")
        placement.forEachIndexed { row, rank ->
            var file = 0
            rank.forEach { ch ->
                if (ch.isDigit()) file += ch.digitToInt() else {
                    val v = (values[ch.lowercaseChar()] ?: 0).toDouble()
                    val sign = if (ch.isUpperCase()) 1 else -1
                    val centerDistance = abs(3.5 - file) + abs(3.5 - row)
                    val positional = if (ch.lowercaseChar() in listOf('n','b')) (7.0-centerDistance)*2 else 0.0
                    score += sign * (v + positional)
                    file++
                }
            }
        }
        return -score
    }
}

@Composable
private fun ChessMindApp(context: Context) {
    val coach = remember { AdaptiveCoach(context) }
    val engine = remember { AdaptiveEngine(coach) }
    val gamePrefs = remember { context.getSharedPreferences("chessmind_saved_game", Context.MODE_PRIVATE) }
    val savedFen = remember { gamePrefs.getString("fen", null) }
    val initialBoard = remember { Board().apply { if (!savedFen.isNullOrBlank()) runCatching { loadFromFen(savedFen) } } }
    var board by remember { mutableStateOf(initialBoard) }
    var fen by remember { mutableStateOf(board.fen) }
    var selected by remember { mutableStateOf<Square?>(null) }
    var legalTargets by remember { mutableStateOf(emptySet<Square>()) }
    var history by remember { mutableStateOf(gamePrefs.getString("history", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()) }
    var sanHistory by remember { mutableStateOf(gamePrefs.getString("san_history", "")?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()) }
    var profile by remember { mutableStateOf(coach.load()) }
    var thinking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(gamePrefs.getString("status", "Твой ход. Начни с белых.") ?: "Твой ход. Начни с белых.") }
    var lastMove by remember { mutableStateOf(gamePrefs.getString("last_move", null)?.split(",")?.takeIf { it.size == 2 }?.let { runCatching { Square.valueOf(it[0]) to Square.valueOf(it[1]) }.getOrNull() }) }
    var blunders by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(true) }
    var languageOpen by remember { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    var authorOpen by remember { mutableStateOf(false) }
    var analysisOpen by remember { mutableStateOf(false) }
    var analysisText by remember { mutableStateOf("") }
    var lastHumanBeforeFen by remember { mutableStateOf(gamePrefs.getString("last_human_fen", null)) }
    var lastHumanMove by remember { mutableStateOf(gamePrefs.getString("last_human_move", null)?.split(",")?.takeIf { it.size == 2 }?.let { runCatching { Square.valueOf(it[0]) to Square.valueOf(it[1]) }.getOrNull() }) }
    var rulesOpen by remember { mutableStateOf(false) }
    var difficulty by remember { mutableIntStateOf(context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).getInt("difficulty", 2).coerceIn(1, 4)) }
    var language by remember { mutableStateOf(context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru") }
    var darkTheme by remember { mutableStateOf(context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).getBoolean("dark_theme", true)) }
    val bgColor = if (darkTheme) forest else Color(0xFFF5F3EE)
    val surfaceColor = if (darkTheme) panel else Color(0xFFFFFFFF)
    val textColor = if (darkTheme) cream else Color(0xFF101827)
    val accentColor = if (darkTheme) green else Color(0xFF101010)
    val gameTextColor = if (darkTheme) cream else Color(0xFF101827)
    val gameMutedColor = if (darkTheme) Color(0xFFD8DCE8) else Color(0xFF263238)
    val dialogTextColor = Color(0xFFF7F3EA)
    val dialogAccentColor = Color(0xFFFFC84A)
    var clockEnabled by remember { mutableStateOf(gamePrefs.getBoolean("clock_enabled", false)) }
    var clockPreset by remember { mutableIntStateOf(gamePrefs.getInt("clock_preset", 5)) }
    var whiteSeconds by remember { mutableIntStateOf(gamePrefs.getInt("white_seconds", 300)) }
    var blackSeconds by remember { mutableIntStateOf(gamePrefs.getInt("black_seconds", 300)) }
    var hintText by remember { mutableStateOf("") }
    fun tr(ru: String, en: String, zh: String, hi: String) = when(language) { "en" -> en; "zh" -> zh; "hi" -> hi; else -> ru }
    val difficultyLabels = listOf(tr("Новичок", "Beginner", "初学者", "शुरुआती"), tr("Лёгкая", "Easy", "简单", "आसान"), tr("Средняя", "Medium", "中等", "मध्यम"), tr("Сложная", "Hard", "困难", "कठिन"))
    LaunchedEffect(language) {
        if (sanHistory.isEmpty() && fen == Board().fen) status = tr("Твой ход. Начни с белых.", "Your move. Start as White.", "轮到你了。白方先行。", "आपकी चाल। सफेद से शुरू करें।")
    }
    LaunchedEffect(fen, history, sanHistory, status, lastMove, lastHumanBeforeFen, lastHumanMove, clockEnabled, clockPreset, whiteSeconds, blackSeconds, blunders) {
        gamePrefs.edit()
            .putString("fen", fen)
            .putString("history", history.joinToString("\n"))
            .putString("san_history", sanHistory.joinToString("\n"))
            .putString("status", status)
            .putString("last_move", lastMove?.let { "${it.first.name},${it.second.name}" })
            .putString("last_human_fen", lastHumanBeforeFen)
            .putString("last_human_move", lastHumanMove?.let { "${it.first.name},${it.second.name}" })
            .putBoolean("clock_enabled", clockEnabled)
            .putInt("clock_preset", clockPreset)
            .putInt("white_seconds", whiteSeconds)
            .putInt("black_seconds", blackSeconds)
            .putInt("blunders", blunders)
            .putBoolean("has_saved_game", history.isNotEmpty() || fen != Board().fen)
            .apply()
    }
    fun clockText(s: Int) = "%02d:%02d".format(s / 60, s % 60)
    fun buildGameAnalysis(): String {
        if (sanHistory.isEmpty()) return "Партия не содержит ходов для анализа."
        return sanHistory.mapIndexed { index, notation ->
            val coords = Regex("([a-h][1-8])-([a-h][1-8])").find(notation)
            val beforeFen = history.getOrNull(index)
            if (coords == null || beforeFen == null) "$notation — ход записан"
            else {
                val from = Square.valueOf(coords.groupValues[1].uppercase())
                val to = Square.valueOf(coords.groupValues[2].uppercase())
                val quality = runCatching { engine.analyzeMove(beforeFen, from, to, 1).lineSequence().firstOrNull().orEmpty() }.getOrDefault("")
                "$notation — $quality"
            }
        }.joinToString("\n")
    }
    fun finishGame(winner: String?, humanWon: Boolean) {
        status = if (winner == null) "Ничья." else "$winner выиграли!"
        profile = coach.finish(profile, humanWon, blunders)
        analysisText = buildGameAnalysis()
        analysisOpen = true
    }
    fun undoTurn() {
        botTurnToken++
        thinking = false
        if (history.isEmpty()) { status = "Пока нечего отменять."; return }
        val count = if (board.sideToMove.toString() == "WHITE" && history.size >= 2) 2 else 1
        val targetIndex = (history.size - count).coerceAtLeast(0)
        board = Board().apply { loadFromFen(history[targetIndex]) }
        history = history.dropLast(minOf(count, history.size))
        sanHistory = sanHistory.dropLast(minOf(count, sanHistory.size))
        lastMove = null
        lastHumanBeforeFen = null
        lastHumanMove = null
        selected = null
        legalTargets = emptySet()
        refresh()
        status = "Ход отменён. Твой ход."
    }
    fun showHint() {
        val position = Board().apply { loadFromFen(board.fen) }
        val legal = position.legalMoves()
        val m = if (legal.isEmpty()) null else engine.bestMove(position, 3)
        hintText = if (m == null) tr("Нет доступных ходов.", "No legal moves.", "没有合法走法。", "कोई वैध चाल नहीं।") else tr("Попробуй ${m.from.name.lowercase()} → ${m.to.name.lowercase()}. Это простой ориентир, не глубокий анализ.", "Try ${m.from.name.lowercase()} → ${m.to.name.lowercase()}. This is a simple suggestion, not deep analysis.", "试试 ${m.from.name.lowercase()} → ${m.to.name.lowercase()}。这是简单建议，并非深度分析。", "${m.from.name.lowercase()} → ${m.to.name.lowercase()} आज़माएँ। यह सरल सुझाव है।")
        hintOpen = true
    }
    LaunchedEffect(clockEnabled, board.sideToMove, whiteSeconds, blackSeconds) {
        if (clockEnabled && !board.isMated && !board.isDraw && whiteSeconds > 0 && blackSeconds > 0) {
            delay(1000)
            if (board.sideToMove.toString() == "WHITE") {
                whiteSeconds = (whiteSeconds - 1).coerceAtLeast(0)
                if (whiteSeconds == 0) status = tr("Время белых истекло. Партия окончена.", "White ran out of time. Game over.", "白方超时。对局结束。", "सफेद का समय समाप्त। खेल खत्म।")
            } else {
                blackSeconds = (blackSeconds - 1).coerceAtLeast(0)
                if (blackSeconds == 0) status = tr("Время чёрных истекло. Партия окончена.", "Black ran out of time. Game over.", "黑方超时。对局结束。", "काले का समय समाप्त। खेल खत्म।")
            }
        }
    }

    fun refresh() { fen = board.fen }
    fun reset() {
        board = Board(); refresh(); selected = null; legalTargets = emptySet()
        history = emptyList(); sanHistory = emptyList(); lastMove = null
        lastHumanBeforeFen = null; lastHumanMove = null
        thinking = false; blunders = 0; whiteSeconds = clockPreset * 60; blackSeconds = clockPreset * 60
        status = tr("Новая партия. Твой ход.", "New game. Your move.", "新游戏。轮到你了。", "नया खेल। आपकी चाल।")
        gamePrefs.edit().clear().apply()
    }
    fun makeBotMove() {
        if (board.isMated || board.isDraw) return
        thinking = true; status = tr("ChessMind думает…", "ChessMind is thinking…", "ChessMind 正在思考…", "ChessMind सोच रहा है…")
        val snapshot = board.fen
        scope.launch {
            val chosen = withContext(Dispatchers.Default) {
                val copy = Board().apply { loadFromFen(snapshot) }
                engine.bestMove(copy, difficulty) ?: return@withContext null
            }
            if (chosen == null) { thinking = false; return@launch }
            history = history + board.fen
            val from = chosen.from; val to = chosen.to
            board.doMove(chosen)
            lastMove = from to to
            sanHistory = sanHistory + "${sanHistory.size / 2 + 1}… ${from.name.lowercase()}${to.name.lowercase()}"
            refresh(); thinking = false; selected = null; legalTargets = emptySet()
            status = when {
                board.isMated -> tr("Мат. Ты выиграл!", "Checkmate. You win!", "将死。你赢了！", "शह-मात। आप जीत गए!")
                board.isDraw -> tr("Ничья.", "Draw.", "和棋。", "ड्रॉ।")
                else -> tr("Твой ход. Посмотри, что изменилось.", "Your move. Look at what changed.", "轮到你了。看看局面变化。", "आपकी चाल। देखें क्या बदला।")
            }
        }
    }
    fun selectSquare(square: Square) {
        if (thinking || board.sideToMove.toString() != "WHITE") return
        if (selected == null) {
            val piece = board.getPiece(square).toString()
            if (piece.startsWith("WHITE")) {
                selected = square
                legalTargets = board.legalMoves().filter { it.from == square }.map { it.to }.toSet()
            }
        } else {
            val from = selected!!
            val move = board.legalMoves().firstOrNull { it.from == from && it.to == square }
            if (move != null) {
                val before = Board().apply { loadFromFen(board.fen) }
                lastHumanBeforeFen = before.fen
                lastHumanMove = from to square
                history = history + board.fen
                val notation = "${sanHistory.size / 2 + 1}. ${from.name.lowercase()}${square.name.lowercase()}"
                board.doMove(move)
                profile = coach.observeMove(profile, before, move, board)
                sanHistory = sanHistory + notation
                lastMove = from to square
                refresh(); selected = null; legalTargets = emptySet()
                if (board.isMated) {
                    status = tr("Мат! Ты выиграл.", "Checkmate! You win.", "将死！你赢了。", "शह-मात! आप जीत गए।")
                    profile = coach.finish(profile, true, blunders)
                } else if (board.isDraw) {
                    status = tr("Ничья.", "Draw.", "和棋。", "ड्रॉ।")
                    profile = coach.finish(profile, false, blunders)
                } else makeBotMove()
            } else {
                val piece = board.getPiece(square).toString()
                if (piece.startsWith("WHITE")) {
                    selected = square
                    legalTargets = board.legalMoves().filter { it.from == square }.map { it.to }.toSet()
                } else { selected = null; legalTargets = emptySet() }
            }
        }
    }

    LaunchedEffect(menuOpen, fen) {
        if (!menuOpen && board.sideToMove.toString() == "BLACK" && !board.isMated && !board.isDraw && !thinking) {
            makeBotMove()
        }
    }

    val scheme = if (darkTheme) darkColorScheme(primary = Color(0xFFFFC84A), onPrimary = Color(0xFF101827), secondary = Color(0xFFD8C9FF), onSecondary = Color(0xFF101827), background = forest, onBackground = Color(0xFFF7F3EA), surface = panel, onSurface = Color(0xFFF7F3EA), surfaceVariant = Color(0xFF263047), onSurfaceVariant = Color(0xFFE0E5F0)) else lightColorScheme(primary = Color(0xFF174D2B), onPrimary = Color.White, secondary = Color(0xFF55358A), onSecondary = Color.White, background = Color(0xFFF5F3EE), onBackground = Color(0xFF080B18), surface = Color.White, onSurface = Color(0xFF080B18), surfaceVariant = Color(0xFFE8EDF3), onSurfaceVariant = Color(0xFF18211D), outline = Color(0xFF536174))
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = bgColor) {
            if (menuOpen) {
                Column(Modifier.fillMaxSize().background(Color(0xFF080B18)).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(10.dp))
                    Image(painter = painterResource(id = com.chessmind.app.R.drawable.ic_chessmind_launcher), contentDescription = "ChessMind — король", modifier = Modifier.fillMaxWidth().height(178.dp), contentScale = ContentScale.Fit)
                    Text("♛ ChessMind", color = cream, fontSize = 38.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
                    Text(tr("ШАХМАТЫ · ИИ · АНАЛИЗ · ОБУЧЕНИЕ", "CHESS · AI · ANALYSIS · TRAINING", "国际象棋 · AI · 分析 · 学习", "शतरंज · AI · विश्लेषण · प्रशिक्षण"), color = Color(0xFFC5C9D8), fontSize = 10.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(28.dp))
                    Button(onClick = { menuOpen = false }, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFC52E), contentColor = Color(0xFF171322)), shape = RoundedCornerShape(17.dp), elevation = ButtonDefaults.buttonElevation(defaultElevation = 7.dp, pressedElevation = 2.dp)) { Text("▶  ${tr("Продолжить", "Continue", "继续", "जारी रखें")}", fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(9.dp))
                    Button(onClick = { reset(); whiteSeconds = clockPreset * 60; blackSeconds = clockPreset * 60; menuOpen = false }, modifier = Modifier.fillMaxWidth().height(54.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF159B61), contentColor = Color.White), shape = RoundedCornerShape(16.dp), elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp, pressedElevation = 2.dp)) { Text("＋  ${tr("Новая игра", "New game", "新游戏", "नया खेल")}", fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(onClick = { difficultyOpen = true }, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(15.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF6552B8))) { Text("▥  ${tr("Сложность: ", "Difficulty: ", "难度：", "कठिनाई: ")}${difficultyLabels[difficulty - 1]}  ›", color = Color(0xFFD8C9FF), fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(9.dp))
                    OutlinedButton(onClick = { rulesOpen = true }, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF314D72))) { Text("♟  ${tr("Правила игры", "How to play", "游戏规则", "खेल के नियम")}", color = cream, maxLines = 1, softWrap = false) }
                    Spacer(Modifier.height(9.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { languageOpen = true }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5A6075))) { Text("◎  ${tr("Язык", "Language", "语言", "भाषा")}", color = cream, maxLines = 1, softWrap = false) }
                        OutlinedButton(onClick = { darkTheme = !darkTheme; context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply() }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5A6075))) { Text(if (darkTheme) "☀  ${tr("Светлая", "Light", "浅色", "हल्की")}" else "☾  ${tr("Тёмная", "Dark", "深色", "गहरी")}", color = cream, maxLines = 1, softWrap = false) }
                    }
                    TextButton(onClick = { authorOpen = true }) { Text(tr("Об авторе", "About", "关于作者", "लेखक के बारे में"), color = Color(0xFFB6BBD0)) }
                }
            } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { menuOpen = true }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("‹", color = accentColor, fontSize = 30.sp, fontWeight = FontWeight.Bold) }
                    OutlinedButton(onClick = { difficultyOpen = true }, shape = RoundedCornerShape(14.dp)) {
                        Text("▥  ${difficultyLabels[difficulty - 1]}  ⌄", color = textColor, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(tr("ИИ-БОТ", "AI BOT", "AI 机器人", "AI बॉट"), color = textColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text(if (thinking) tr("Думает…", "Thinking…", "思考中…", "सोच रहा है…") else "♛  ${profile.rating}", color = accentColor, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!menuOpen) ChessBoard(fen, selected, legalTargets, lastMove) { selectSquare(it) }
                Spacer(Modifier.height(12.dp))
                if (clockEnabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(modifier = Modifier.weight(1f), color = if (board.sideToMove.toString() == "WHITE") Color(0xFF27364E) else surfaceColor, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (board.sideToMove.toString() == "WHITE") Color(0xFFFFC84A) else Color(0xFF45516A))) {
                            Text("♙  ${clockText(whiteSeconds)}", Modifier.padding(vertical = 12.dp), color = Color(0xFFF7F3EA), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                        Surface(modifier = Modifier.weight(1f), color = if (board.sideToMove.toString() == "BLACK") Color(0xFF27364E) else surfaceColor, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (board.sideToMove.toString() == "BLACK") Color(0xFFFFC84A) else Color(0xFF45516A))) {
                            Text("♟  ${clockText(blackSeconds)}", Modifier.padding(vertical = 12.dp), color = Color(0xFFF7F3EA), fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                    TextButton(onClick = { clockSettingsOpen = true }) { Text("◷  ${clockPreset} мин", color = accentColor) }
                }
                Spacer(Modifier.height(8.dp))
                Surface(color = if (darkTheme) panel else Color(0xFFE8EDF3), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Text(status, color = gameTextColor, fontWeight = FontWeight.SemiBold)
                        Text(tr("Профиль: ${styleName(profile)} · сложность $difficulty/4", "Profile: ${styleName(profile)} · difficulty $difficulty/4", "档案：${styleName(profile)} · 难度 $difficulty/4", "प्रोफ़ाइल: ${styleName(profile)} · कठिनाई $difficulty/4"), color = accentColor, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard(tr("ПАРТИЙ", "GAMES", "对局", "खेल"), profile.games.toString(), Modifier.weight(1f), surfaceColor, textColor, accentColor)
                    StatCard(tr("ПОБЕД", "WINS", "胜利", "जीत"), profile.wins.toString(), Modifier.weight(1f), surfaceColor, textColor, accentColor)
                    StatCard(tr("УРОВЕНЬ", "LEVEL", "等级", "स्तर"), when {
                        profile.rating < 750 -> tr("Старт", "Beginner", "入门", "शुरुआती")
                        profile.rating < 1000 -> tr("База", "Basic", "基础", "बुनियादी")
                        profile.rating < 1300 -> tr("Рост", "Improving", "进阶", "प्रगति")
                        else -> tr("Сильный", "Advanced", "高级", "उन्नत")
                    }, Modifier.weight(1f), surfaceColor, textColor, accentColor)
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Button(onClick = { showHint() }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1769D2), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 3.dp)) {
                        Text("☼  ${tr("Подсказка", "Hint", "提示", "संकेत")}", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, fontSize = 11.sp)
                    }
                    Button(onClick = { analysisText = if (lastHumanBeforeFen != null && lastHumanMove != null) engine.analyzeMove(lastHumanBeforeFen!!, lastHumanMove!!.first, lastHumanMove!!.second, difficulty.coerceAtMost(3)) else tr("Сделай ход, затем нажми «Анализ».", "Make a move, then tap Analysis.", "先走一步，然后点击分析。", "पहले चाल चलें, फिर विश्लेषण दबाएँ।"); analysisOpen = true }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(15.dp), contentPadding = PaddingValues(horizontal = 3.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF159B61), contentColor = Color.White)) {
                        Text("▥  ${tr("Анализ", "Analyze", "分析", "विश्लेषण")}", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, fontSize = 11.sp)
                    }
                    Button(onClick = { undoTurn() }, modifier = Modifier.weight(1f).height(54.dp), shape = RoundedCornerShape(15.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97716), contentColor = Color.White), contentPadding = PaddingValues(horizontal = 3.dp)) {
                        Text("↶  ${tr("Отмена", "Undo", "悔棋", "पूर्ववत")}", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, fontSize = 11.sp)
                    }
}