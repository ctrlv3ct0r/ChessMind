package com.chessmind.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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

    fun refresh() { fen = board.fen }
    fun reset() {
        board = Board(); refresh(); selected = null; legalTargets = emptySet()
        history = emptyList(); sanHistory = emptyList(); lastMove = null
        thinking = false; blunders = 0; status = "Новая партия. Твой ход."
    }
    fun makeBotMove() {
        if (board.isMated || board.isDraw) return
        thinking = true; status = "ChessMind думает…"
        val snapshot = board.fen
        scope.launch {
            val chosen = withContext(Dispatchers.Default) {
                val copy = Board().apply { loadFromFen(snapshot) }
                engine.choose(copy, profile)
            }
            history = history + board.fen
            val from = chosen.from; val to = chosen.to
            board.doMove(chosen)
            lastMove = from to to
            sanHistory = sanHistory + "${sanHistory.size / 2 + 1}… ${from.name.lowercase()}${to.name.lowercase()}"
            refresh(); thinking = false; selected = null; legalTargets = emptySet()
            status = when {
                board.isMated -> "Мат. Ты выиграл!"
                board.isDraw -> "Ничья."
                else -> "Твой ход. Посмотри, что изменилось."
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
                    status = "Мат! Ты выиграл."
                    profile = coach.finish(profile, true, blunders)
                } else if (board.isDraw) {
                    status = "Ничья."
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

    MaterialTheme(colorScheme = darkColorScheme(primary = green, background = forest, surface = panel, onSurface = cream)) {
        Surface(Modifier.fillMaxSize(), color = forest) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("CHESSMIND", color = cream, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                        Text("АДАПТИВНЫЙ ШАХМАТНЫЙ ТРЕНЕР", color = green, fontSize = 9.sp, letterSpacing = 1.5.sp)
                    }
                    Surface(color = panel, shape = RoundedCornerShape(14.dp)) {
                        Text("♟ ${profile.rating}", Modifier.padding(horizontal = 14.dp, vertical = 9.dp), color = cream, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("CHESSMIND", color = cream, fontWeight = FontWeight.Bold)
                        Text("Тренер · динамический стиль", color = green, fontSize = 11.sp)
                    }
                    Text(if (thinking) "ДУМАЕТ…" else "● ОНЛАЙН ЛОКАЛЬНО", color = if (thinking) Color(0xFFFFC66D) else green, fontSize = 10.sp)
                }
                Spacer(Modifier.height(8.dp))
                ChessBoard(fen, selected, legalTargets, lastMove) { selectSquare(it) }
                Spacer(Modifier.height(12.dp))
                Surface(color = panel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(13.dp)) {
                        Text(status, color = cream, fontWeight = FontWeight.SemiBold)
                        Text("Профиль: ${styleName(profile)} · цель обучения: следующий уровень", color = green, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("ПАРТИЙ", profile.games.toString(), Modifier.weight(1f))
                    StatCard("ПОБЕД", profile.wins.toString(), Modifier.weight(1f))
                    StatCard("УРОВЕНЬ", when {
                        profile.rating < 750 -> "Старт"
                        profile.rating < 1000 -> "База"
                        profile.rating < 1300 -> "Рост"
                        else -> "Сильный"
                    }, Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (history.isNotEmpty() && !thinking) {
                            board = Board().apply { loadFromFen(history.last()) }
                            history = history.dropLast(1)
                            if (sanHistory.isNotEmpty()) sanHistory = sanHistory.dropLast(1)
                            refresh(); status = "Ход отменён."
                        }
                    }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = panel)) {
                        Text("↶ Отмена")
                    }
                    Button(onClick = { reset() }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = forest)) {
                        Text("Новая партия", fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("ИСТОРИЯ ХОДОВ", Modifier.fillMaxWidth(), color = green, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(if (sanHistory.isEmpty()) "Партия ещё не началась" else sanHistory.takeLast(8).joinToString("   "),
                    Modifier.fillMaxWidth(), color = cream, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text("ТВОЯ ЗАДАЧА — СТАТЬ СИЛЬНЕЕ. НЕ ПРОСТО ВЫИГРАТЬ.", color = green, fontSize = 9.sp, letterSpacing = 0.6.sp)
            }
        }
    }
}

private fun styleName(p: PlayerProfile): String = when {
    p.aggressive > p.games * 2 -> "Атакующий"
    p.tactical > p.games * 2 -> "Тактический"
    else -> "Исследуется"
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = panel, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, color = cream, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            Text(label, color = green, fontSize = 9.sp, letterSpacing = 0.8.sp)
        }
    }
}

@Composable
private fun ChessBoard(fen: String, selected: Square?, targets: Set<Square>, lastMove: Pair<Square, Square>?, onTap: (Square) -> Unit) {
    val squares = remember(fen) { parseFenBoard(fen) }
    Column(Modifier.fillMaxWidth().aspectRatio(1f).padding(2.dp)) {
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