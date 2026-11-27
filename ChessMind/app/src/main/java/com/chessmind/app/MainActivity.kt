package com.chessmind.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
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

private val forest = Color(0xFF14211C)
private val panel = Color(0xFF20332B)
private val cream = Color(0xFFF0E6D2)
private val green = Color(0xFF9DBB83)
private val darkSquare = Color(0xFF66816D)
private val lightSquare = Color(0xFFE8E2D2)

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
    var board by remember { mutableStateOf(Board()) }
    var fen by remember { mutableStateOf(board.fen) }
    var selected by remember { mutableStateOf<Square?>(null) }
    var legalTargets by remember { mutableStateOf(emptySet<Square>()) }
    var history by remember { mutableStateOf(emptyList<String>()) }
    var sanHistory by remember { mutableStateOf(emptyList<String>()) }
    var profile by remember { mutableStateOf(coach.load()) }
    var thinking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Твой ход. Начни с белых.") }
    var lastMove by remember { mutableStateOf<Pair<Square, Square>?>(null) }
    var blunders by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(true) }
    var languageOpen by remember { mutableStateOf(false) }
    var hintOpen by remember { mutableStateOf(false) }
    var authorOpen by remember { mutableStateOf(false) }
    var chatOpen by remember { mutableStateOf(false) }
    var language by remember { mutableStateOf(context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).getString("language", "ru") ?: "ru") }
    var darkTheme by remember { mutableStateOf(context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).getBoolean("dark_theme", true)) }
    val bgColor = if (darkTheme) forest else Color(0xFFF5F3EE)
    val surfaceColor = if (darkTheme) panel else Color(0xFFFFFFFF)
    val textColor = if (darkTheme) cream else Color(0xFF18211D)
    val accentColor = if (darkTheme) green else Color(0xFF315F3D)
    var clockEnabled by remember { mutableStateOf(false) }
    var clockPreset by remember { mutableIntStateOf(5) }
    var whiteSeconds by remember { mutableIntStateOf(300) }
    var blackSeconds by remember { mutableIntStateOf(300) }
    var chatInput by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf(listOf("ChessMind: Привет! Спроси меня о шахматах.")) }
    var hintText by remember { mutableStateOf("") }
    fun tr(ru: String, en: String, zh: String, hi: String) = when(language) { "en" -> en; "zh" -> zh; "hi" -> hi; else -> ru }
    val difficultyLabels = listOf(tr("Новичок", "Beginner", "初学者", "शुरुआती"), tr("Лёгкая", "Easy", "简单", "आसान"), tr("Средняя", "Medium", "中等", "मध्यम"), tr("Сложная", "Hard", "困难", "कठिन"))
    LaunchedEffect(language) {
        if (sanHistory.isEmpty()) status = tr("Твой ход. Начни с белых.", "Your move. Start as White.", "轮到你了。白方先行。", "आपकी चाल। सफेद से शुरू करें।")
    }
    fun clockText(s: Int) = "%02d:%02d".format(s / 60, s % 60)
    fun showHint() {
        val position = Board().apply { loadFromFen(board.fen) }
        val legal = position.legalMoves()
        val m = if (legal.isEmpty()) null else engine.bestMove(position, 3)
        hintText = if (m == null) tr("Нет доступных ходов.", "No legal moves.", "没有合法走法。", "कोई वैध चाल नहीं।") else tr("Попробуй ${m.from.name.lowercase()} → ${m.to.name.lowercase()}. Это простой ориентир, не глубокий анализ.", "Try ${m.from.name.lowercase()} → ${m.to.name.lowercase()}. This is a simple suggestion, not deep analysis.", "试试 ${m.from.name.lowercase()} → ${m.to.name.lowercase()}。这是简单建议，并非深度分析。", "${m.from.name.lowercase()} → ${m.to.name.lowercase()} आज़माएँ। यह सरल सुझाव है।")
        hintOpen = true
    }
    fun sendChat() {
        val q = chatInput.trim()
        if (q.isNotEmpty()) {
            val pos = Board().apply { loadFromFen(board.fen) }
            val legalCount = pos.legalMoves().size
            val side = if (pos.sideToMove.toString() == "WHITE") tr("белых", "White", "白方", "सफेद") else tr("чёрных", "Black", "黑方", "काले")
            val a = when {
                q.contains("подсказ", true) || q.contains("hint", true) || q.contains("ход", true) || q.contains("move", true) -> {
                    val best = if (legalCount > 0) engine.bestMove(pos, 3) else null
                    if (best == null) tr("В этой позиции ходов нет: партия завершена.", "There are no legal moves: the game is over.", "当前没有合法走法：对局已结束。", "कोई वैध चाल नहीं: खेल समाप्त हो गया है।")
                    else tr("Попробуй ${best.from.name.lowercase()} → ${best.to.name.lowercase()}. Это рекомендация встроенного эвристического движка, а не экспертный анализ.", "Try ${best.from.name.lowercase()} → ${best.to.name.lowercase()}. This is a recommendation from the built-in heuristic engine, not expert analysis.", "可以尝试 ${best.from.name.lowercase()} → ${best.to.name.lowercase()}。这是内置启发式引擎的建议，并非专家级分析。", "${best.from.name.lowercase()} → ${best.to.name.lowercase()} आज़माएँ। यह अंतर्निहित इंजन का सुझाव है।")
                }
                q.contains("позици", true) || q.contains("position", true) || q.contains("сейчас", true) -> tr("Сейчас ход ${side}. Доступно легальных ходов: $legalCount. Я могу предложить вариант по запросу «подскажи ход».", "It is ${side} to move. Legal moves available: $legalCount. Ask me to suggest a move for a candidate.", "现在轮到${side}。可用合法走法：$legalCount。你可以让我推荐一步。", "अब ${side} की चाल है। वैध चालें: $legalCount। सुझाव के लिए मुझसे चाल पूछें।")
                q.contains("правил", true) || q.contains("rule", true) -> tr("Цель — поставить мат королю соперника. Шах означает нападение на короля; из-под шаха нужно обязательно выйти.", "The goal is to checkmate the opponent’s king. Check means the king is under attack and must be protected.", "目标是将死对方的王。将军表示王受到攻击，必须应将。", "लक्ष्य प्रतिद्वंद्वी के राजा को शह-मात देना है। शह में राजा पर हमला होता है और उससे बचना अनिवार्य है।")
                else -> tr("Я могу оценить текущую позицию, подсказать ход или объяснить базовые правила. Спроси, например: «Что сейчас происходит?» или «Подскажи ход».", "I can describe the current position, suggest a move, or explain basic rules. Try: “What is happening?” or “Suggest a move.”", "我可以介绍当前局面、推荐走法或解释基本规则。可以问：“现在局面如何？”或“推荐一步棋”。", "मैं वर्तमान स्थिति समझा सकता हूँ, चाल सुझा सकता हूँ या बुनियादी नियम बता सकता हूँ। पूछें: “अभी स्थिति क्या है?”")
            }
            chatMessages = chatMessages + tr("Вы: $q", "You: $q", "你：$q", "आप: $q") + "ChessMind: $a"
            chatInput = ""
        }
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
        thinking = false; blunders = 0; status = tr("Новая партия. Твой ход.", "New game. Your move.", "新游戏。轮到你了。", "नया खेल। आपकी चाल।")
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

    val scheme = if (darkTheme) darkColorScheme(primary = green, background = forest, surface = panel, onSurface = cream) else lightColorScheme(primary = Color(0xFF315F3D), background = Color(0xFFF5F3EE), surface = Color.White, onSurface = Color(0xFF18211D), onPrimary = Color.White, onBackground = Color(0xFF18211D))
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = bgColor) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("CHESSMIND", color = textColor, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text(tr("АДАПТИВНЫЙ ШАХМАТНЫЙ ТРЕНЕР", "ADAPTIVE CHESS COACH", "自适应国际象棋教练", "अनुकूली शतरंज कोच"), color = accentColor, fontSize = 9.sp, letterSpacing = 1.1.sp)
                    }
                    Surface(color = surfaceColor, shape = RoundedCornerShape(14.dp)) {
                        Text("♟ ${profile.rating}", Modifier.padding(horizontal = 10.dp, vertical = 9.dp), color = textColor, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.weight(1f)) { Text(tr("Меню", "Menu", "菜单", "मेनू"), color = textColor, maxLines = 1, softWrap = false, fontSize = 12.sp) }
                    OutlinedButton(onClick = { languageOpen = true }, modifier = Modifier.weight(1f)) { Text(tr("Язык", "Language", "语言", "भाषा"), color = textColor, maxLines = 1, softWrap = false, fontSize = 12.sp) }
                    Button(onClick = { chatOpen = true }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = accentColor, contentColor = bgColor)) { Text(tr("Чат", "Chat", "聊天", "चैट"), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, fontSize = 12.sp) }
                }
                OutlinedButton(onClick = { darkTheme = !darkTheme; context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).edit().putBoolean("dark_theme", darkTheme).apply() }, modifier = Modifier.fillMaxWidth()) { Text(if (darkTheme) tr("☀ Светлая тема", "☀ Light theme", "☀ 浅色主题", "☀ हल्की थीम") else tr("☾ Тёмная тема", "☾ Dark theme", "☾ 深色主题", "☾ गहरी थीम"), color = textColor, maxLines = 1, softWrap = false) }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CHESSMIND", color = textColor, fontWeight = FontWeight.Bold)
                        Text(tr("Тренер · динамический стиль", "Coach · adaptive style", "教练 · 自适应风格", "कोच · अनुकूली शैली"), color = accentColor, fontSize = 11.sp)
                    }
                    Text(if (thinking) tr("ДУМАЕТ…", "THINKING…", "思考中…", "सोच रहा है…") else tr("● ОНЛАЙН ЛОКАЛЬНО", "● OFFLINE READY", "● 本地运行", "● ऑफ़लाइन तैयार"), color = if (thinking) Color(0xFFFFC66D) else green, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                if (!menuOpen) ChessBoard(fen, selected, legalTargets, lastMove) { selectSquare(it) }
                Spacer(Modifier.height(12.dp))
                Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Text(status, color = cream, fontWeight = FontWeight.SemiBold)
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
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (history.isNotEmpty() && !thinking) {
                            board = Board().apply { loadFromFen(history.last()) }
                            history = history.dropLast(1)
                            if (sanHistory.isNotEmpty()) sanHistory = sanHistory.dropLast(1)
                            refresh(); status = tr("Ход отменён.", "Move undone.", "已撤销一步。", "चाल वापस ली गई।")
                        }
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD97736), contentColor = Color.White)) {
                        Text("↶ ${tr("Отмена", "Undo", "悔棋", "पूर्ववत")}", fontWeight = FontWeight.Bold)
                    }
                    Button(onClick = { reset() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = forest)) {
                        Text(tr("Новая партия", "New game", "新游戏", "नया खेल"), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showHint() }, modifier = Modifier.weight(1f)) { Text(tr("Подсказка", "Hint", "提示", "संकेत"), color = textColor, maxLines = 1, softWrap = false, fontSize = 11.sp) }
                    Button(onClick = { analysisText = if (lastHumanBeforeFen != null && lastHumanMove != null) engine.analyzeMove(lastHumanBeforeFen!!, lastHumanMove!!.first, lastHumanMove!!.second, difficulty.coerceAtMost(3)) else tr("Сделай ход, затем нажми «Анализ».", "Make a move, then tap Analysis.", "先走一步，然后点击分析。", "पहले चाल चलें, फिर विश्लेषण दबाएँ।"); analysisOpen = true }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27834B), contentColor = Color.White)) { Text(tr("Анализ", "Analyze", "分析", "विश्लेषण"), maxLines = 1, softWrap = false, fontSize = 11.sp) }
                    OutlinedButton(onClick = { clockEnabled = !clockEnabled }, modifier = Modifier.weight(1f)) { Text(tr("Часы", "Clock", "计时", "घड़ी"), color = textColor, maxLines = 1, softWrap = false, fontSize = 11.sp) }
                }
                if (clockEnabled) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("♙ ${clockText(whiteSeconds)}", color = if (board.sideToMove.toString() == "WHITE") Color(0xFFFFC66D) else cream, fontWeight = FontWeight.Bold)
                        Text("♟ ${clockText(blackSeconds)}", color = if (board.sideToMove.toString() == "BLACK") Color(0xFFFFC66D) else cream, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { clockPreset = if (clockPreset == 5) 10 else 5; whiteSeconds = clockPreset * 60; blackSeconds = clockPreset * 60 }) { Text("${clockPreset}+0", color = green) }
                    }
                }
                Text(tr("ИСТОРИЯ ХОДОВ", "MOVE HISTORY", "走棋记录", "चाल इतिहास"), Modifier.fillMaxWidth(), color = accentColor, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(if (sanHistory.isEmpty()) tr("Партия ещё не началась", "Game has not started", "对局尚未开始", "खेल अभी शुरू नहीं हुआ") else sanHistory.takeLast(8).joinToString("   "),
                    Modifier.fillMaxWidth(), color = cream, fontSize = 11.sp)
                Text(tr("ТВОЯ ЗАДАЧА — СТАТЬ СИЛЬНЕЕ. НЕ ПРОСТО ВЫИГРАТЬ.", "YOUR GOAL IS TO IMPROVE, NOT JUST WIN.", "目标是不断进步，而不只是获胜。", "लक्ष्य बेहतर बनना है, सिर्फ जीतना नहीं।"), color = green, fontSize = 9.sp, letterSpacing = 0.6.sp)
            }
        }
    }
    if (menuOpen) AlertDialog(onDismissRequest = { menuOpen = false },
        title = { Text(tr("Меню", "Menu", "菜单", "मेनू"), color = textColor) },
        text = { Column {
            TextButton(onClick = { menuOpen = false }) { Text(tr("Продолжить", "Continue", "继续", "जारी रखें"), color = textColor) }
            TextButton(onClick = { reset(); whiteSeconds = clockPreset * 60; blackSeconds = clockPreset * 60; menuOpen = false }) { Text(tr("Новая игра", "New game", "新游戏", "नया खेल"), color = textColor) }
            TextButton(onClick = { menuOpen = false; authorOpen = true }) { Text(tr("Автор", "Author", "作者", "लेखक"), color = textColor) }
            TextButton(onClick = { menuOpen = false; languageOpen = true }) { Text(tr("Выбор языка", "Language", "选择语言", "भाषा"), color = textColor) }
            Text(tr("Сложность бота", "Bot difficulty", "机器人难度", "बॉट कठिनाई"), color = textColor, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { difficultyLabels.forEachIndexed { i, label -> TextButton(onClick = { difficulty = i + 1; context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).edit().putInt("difficulty", difficulty).apply() }, contentPadding = PaddingValues(horizontal = 5.dp)) { Text(label, color = if (difficulty == i + 1) accentColor else textColor, fontSize = 11.sp, maxLines = 1, softWrap = false) } } }
        } },
        confirmButton = { TextButton(onClick = { menuOpen = false }) { Text(tr("Закрыть", "Close", "关闭", "बंद करें"), color = accentColor) } })
    if (languageOpen) AlertDialog(onDismissRequest = { languageOpen = false }, title = { Text(tr("Выбор языка", "Choose language", "选择语言", "भाषा चुनें"), color = textColor) },
        text = { Column {
            listOf("ru" to "Русский", "en" to "English", "zh" to "中文", "hi" to "हिन्दी").forEach { (code, label) ->
                TextButton(onClick = { language = code; context.getSharedPreferences("chessmind_settings", Context.MODE_PRIVATE).edit().putString("language", code).apply(); languageOpen = false }) { Text(label, color = if (language == code) accentColor else textColor, maxLines = 1, softWrap = false) }
            }
        } }, confirmButton = { TextButton(onClick = { languageOpen = false }) { Text("OK") } })
    if (authorOpen) AlertDialog(onDismissRequest = { authorOpen = false }, title = { Text("ChessMind", color = textColor) },
        text = { Text(tr("Автор проекта: ChessMind. Версия прототипа.", "Project author: ChessMind. Prototype version.", "项目作者：ChessMind。原型版本。", "परियोजना लेखक: ChessMind। प्रोटोटाइप संस्करण।")) },
        confirmButton = { TextButton(onClick = { authorOpen = false }) { Text("OK") } })
    if (hintOpen) AlertDialog(onDismissRequest = { hintOpen = false }, title = { Text(tr("Подсказка тренера", "Coach hint", "教练提示", "कोच संकेत"), color = textColor) },
        text = { Text(hintText, color = textColor) }, confirmButton = { TextButton(onClick = { hintOpen = false }) { Text("OK") } })
    if (chatOpen) AlertDialog(onDismissRequest = { chatOpen = false }, title = { Text(tr("Чат с ChessMind", "Chat with ChessMind", "与 ChessMind 聊天", "ChessMind से चैट"), color = textColor) },
        text = { Column {
            LazyColumn(Modifier.heightIn(max = 220.dp)) { itemsIndexed(chatMessages) { _, msg -> Text(msg, color = textColor, modifier = Modifier.padding(vertical = 4.dp)) } }
            OutlinedTextField(value = chatInput, onValueChange = { chatInput = it }, label = { Text(tr("Сообщение", "Message", "消息", "संदेश"), color = textColor) }, textStyle = androidx.compose.ui.text.TextStyle(color = textColor), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = textColor, unfocusedTextColor = textColor, focusedBorderColor = accentColor, unfocusedBorderColor = accentColor, focusedLabelColor = accentColor, unfocusedLabelColor = textColor))
        } }, confirmButton = { Row {
            TextButton(onClick = { sendChat() }) { Text(tr("Отправить", "Send", "发送", "भेजें"), color = accentColor) }
            TextButton(onClick = { chatOpen = false }) { Text(tr("Закрыть", "Close", "关闭", "बंद करें"), color = accentColor) }
        } })
    if (analysisOpen) AlertDialog(onDismissRequest = { analysisOpen = false }, title = { Text(tr("Анализ хода", "Move analysis", "走法分析", "चाल विश्लेषण"), color = textColor) }, text = { Text(analysisText, color = textColor) }, confirmButton = { TextButton(onClick = { analysisOpen = false }) { Text(tr("Закрыть", "Close", "关闭", "बंद करें"), color = accentColor) } })
}

private fun styleName(p: PlayerProfile): String = when {
    p.aggressive > p.games * 2 -> "Атакующий"
    p.tactical > p.games * 2 -> "Тактический"
    else -> "Исследуется"
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier, surfaceColor: Color = panel, textColor: Color = cream, accentColor: Color = green) {
    Surface(modifier, color = surfaceColor, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = textColor, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(label, color = accentColor, fontSize = 9.sp, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun ChessBoard(fen: String, selected: Square?, targets: Set<Square>, lastMove: Pair<Square, Square>?, onTap: (Square) -> Unit) {
    val squares = remember(fen) { parseFenBoard(fen) }
    Column(Modifier.fillMaxWidth().widthIn(max = 440.dp).aspectRatio(1f).padding(2.dp)) {
        for (row in 0..7) {
            Row(Modifier.weight(1f)) {
                for (col in 0..7) {
                    val square = Square.values().first { it.name == "${'A' + col}${8 - row}" }
                    val piece = squares[row][col]
                    val isLast = lastMove?.let { square == it.first || square == it.second } ?: false
                    val bg = when {
                        selected == square -> Color(0xFFE6C65B)
                        square in targets -> Color(0xFF9AB77A)
                        isLast -> Color(0xFFB9C78B)
                        (row + col) % 2 == 0 -> lightSquare
                        else -> darkSquare
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(bg).clickable { onTap(square) }, contentAlignment = Alignment.Center) {
                        if (piece != null) Text(pieceGlyph(piece), fontSize = 34.sp,
                            color = if (piece.isUpperCase()) Color(0xFFFFFCF3) else Color(0xFF18211D))
                        if (targets.contains(square) && piece == null) {
                            Box(Modifier.size(10.dp).background(Color(0xFF365B3C), RoundedCornerShape(50)))
                        }
                        if (col == 0) Text("${8-row}", Modifier.align(Alignment.TopStart).padding(2.dp),
                            color = Color(0xFF26352D), fontSize = 8.sp)
                        if (row == 7) Text("${'a'+col}", Modifier.align(Alignment.BottomEnd).padding(2.dp),
                            color = Color(0xFF26352D), fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

private fun parseFenBoard(fen: String): List<List<Char?>> {
    val rows = fen.substringBefore(' ').split('/')
    return rows.map { row ->
        val cells = mutableListOf<Char?>()
        row.forEach { ch ->
            if (ch.isDigit()) repeat(ch.digitToInt()) { cells.add(null) } else cells.add(ch)
        }
        cells
    }
}

private fun pieceGlyph(piece: Char): String = when (piece) {
    'K' -> "♔"; 'Q' -> "♕"; 'R' -> "♖"; 'B' -> "♗"; 'N' -> "♘"; 'P' -> "♙"
    'k' -> "♚"; 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; 'n' -> "♞"; 'p' -> "♟"; else -> ""

}