package com.example.clearsight

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.clearsight.ui.theme.ClearSightTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import kotlin.math.pow

// Simple local storage using DataStore (JSON sessions)
private val Context.dataStore by preferencesDataStore(name = "clearsight_prefs")
private val KEY_SESSIONS = stringPreferencesKey("sessions_json")

data class SessionResult(
    val timestamp: Long,
    val trials: Int,
    val thresholdNorm: Float // fraction of min(screenW, screenH)
)

class SessionsRepository(private val context: Context) {
    val sessionsFlow: StateFlow<List<SessionResult>> get() = _sessions
    private val _sessions = MutableStateFlow<List<SessionResult>>(emptyList())

    suspend fun load() {
        val json = context.dataStore.data.map { prefs -> prefs[KEY_SESSIONS] ?: "[]" }.first()
        _sessions.value = parse(json)
    }

    suspend fun append(result: SessionResult) {
        val current = _sessions.value.toMutableList().apply { add(0, result) }
        _sessions.value = current
        val json = toJson(current)
        context.dataStore.edit { prefs -> prefs[KEY_SESSIONS] = json }
    }

    private fun parse(json: String): List<SessionResult> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionResult(
                timestamp = o.getLong("timestamp"),
                trials = o.getInt("trials"),
                thresholdNorm = o.getDouble("thresholdNorm").toFloat()
            )
        }
    }
    private fun toJson(list: List<SessionResult>): String {
        val arr = JSONArray()
        list.forEach { r ->
            val o = JSONObject()
            o.put("timestamp", r.timestamp)
            o.put("trials", r.trials)
            o.put("thresholdNorm", r.thresholdNorm.toDouble())
            arr.put(o)
        }
        return arr.toString()
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClearSightTheme {
                val nav = rememberNavController()
                val repo = remember { SessionsRepository(this) }
                LaunchedEffect(Unit) { repo.load() }
                AppNav(nav, repo)
            }
        }
    }
}

@Composable
fun AppNav(nav: NavHostController, repo: SessionsRepository) {
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                startSession = { nav.navigate("session") },
                openHistory = { nav.navigate("history") }
            )
        }
        composable("session") {
            SessionScreen(
                onDone = { result ->
                    LaunchedEffect(result) {
                        repo.append(result)
                        nav.popBackStack("home", inclusive = false)
                        nav.navigate("history")
                    }
                },
                onCancel = { nav.popBackStack() }
            )
        }
        composable("history") {
            HistoryScreen(repo = repo, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
fun HomeScreen(startSession: () -> Unit, openHistory: () -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("ClearSight") })
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Exercice visuel type « Landolt C »", fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "But : indiquer l’orientation de la brèche (haut/bas/gauche/droite). " +
                        "Difficulté adaptative (3 bons → plus petit, 1 erreur → plus grand). " +
                        "Ce n’est pas un dispositif médical.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = startSession, modifier = Modifier.fillMaxWidth()) {
                Text("Démarrer une session")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = openHistory, modifier = Modifier.fillMaxWidth()) {
                Text("Historique")
            }
        }
    }
}

enum class GapDir { UP, RIGHT, DOWN, LEFT }

@Composable
fun SessionScreen(onDone: (SessionResult) -> Unit, onCancel: () -> Unit) {
    val totalTrials = 30
    val minDimPx = with(LocalConfiguration.current) {
        min(screenWidthDp, screenHeightDp) * (resources.displayMetrics.density)
    }
    var trialIndex by remember { mutableStateOf(0) }
    var correctStreak by remember { mutableStateOf(0) }
    var lastChangeWasDown by remember { mutableStateOf<Boolean?>(null) }
    var reversals by remember { mutableStateOf(mutableListOf<Float>()) }

    var sizeNorm by remember { mutableStateOf(0.18f) } // initial: 18% of min dim
    val minNorm = 0.03f
    val maxNorm = 0.5f
    val stepDown = 0.85f
    val stepUp = 1.2f

    var currentGap by remember { mutableStateOf(GapDir.values().random()) }
    var feedback by remember { mutableStateOf<String?>(null) }

    fun nextTrial() {
        currentGap = GapDir.values().random()
    }

    fun registerReversal(newDown: Boolean) {
        if (lastChangeWasDown != null && lastChangeWasDown != newDown) {
            reversals.add(sizeNorm)
        }
        lastChangeWasDown = newDown
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Session ${trialIndex + 1} / $totalTrials") },
                navigationIcon = {
                    TextButton(onClick = onCancel) { Text("Quitter") }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Stimulus
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                LandoltC(sizePx = (sizeNorm * minDimPx).coerceIn(12f, minDimPx * maxNorm), gap = currentGap)
            }
            feedback?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }
            // Controls
            Text("Indique l’orientation de la brèche :", fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            DirectionPad(onDirection = { dir ->
                val correct = dir == currentGap
                feedback = if (correct) "✔️ Correct" else "❌ Faux (c’était ${'$'}{currentGap.name.lowercase()})"
                if (correct) {
                    correctStreak += 1
                    if (correctStreak >= 3) {
                        // make smaller (harder)
                        val old = sizeNorm
                        sizeNorm = (sizeNorm * stepDown).coerceAtLeast(minNorm)
                        correctStreak = 0
                        registerReversal(newDown = true)
                    }
                } else {
                    correctStreak = 0
                    val old = sizeNorm
                    sizeNorm = (sizeNorm * stepUp).coerceAtMost(maxNorm)
                    registerReversal(newDown = false)
                }
                if (trialIndex + 1 >= totalTrials) {
                    // compute threshold (geometric mean of reversals, else mean of last 10 sizes)
                    val sizes = if (reversals.size >= 4) reversals else List(10) { sizeNorm }
                    val geom = sizes.fold(1.0) { acc, v -> acc * v.toDouble().coerceAtLeast(1e-5) }.pow(1.0 / sizes.size)
                    val result = SessionResult(
                        timestamp = System.currentTimeMillis(),
                        trials = totalTrials,
                        thresholdNorm = geom.toFloat()
                    )
                    onDone(result)
                } else {
                    trialIndex += 1
                    nextTrial()
                }
            })
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun DirectionPad(onDirection: (GapDir) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onDirection(GapDir.UP) }, modifier = Modifier.width(160.dp)) {
                Text("Haut")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onDirection(GapDir.LEFT) }, modifier = Modifier.weight(1f)) {
                Text("Gauche")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onDirection(GapDir.RIGHT) }, modifier = Modifier.weight(1f)) {
                Text("Droite")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { onDirection(GapDir.DOWN) }, modifier = Modifier.width(160.dp)) {
                Text("Bas")
            }
        }
    }
}

@Composable
fun LandoltC(sizePx: Float, gap: GapDir) {
    val stroke = sizePx * 0.18f // ring thickness
    val gapAngle = 40f
    val startAngle = when (gap) {
        GapDir.RIGHT -> -gapAngle / 2f
        GapDir.UP -> 90f - gapAngle / 2f
        GapDir.LEFT -> 180f - gapAngle / 2f
        GapDir.DOWN -> 270f - gapAngle / 2f
    }
    Canvas(modifier = Modifier.size((sizePx + stroke).dp)) {
        val diameter = sizePx
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        drawArc(
            color = androidx.compose.ui.graphics.Color.Black,
            startAngle = startAngle,
            sweepAngle = 360f - gapAngle,
            useCenter = false,
            topLeft = topLeft,
            size = Size(diameter, diameter),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun HistoryScreen(repo: SessionsRepository, onBack: () -> Unit) {
    val sessions by repo.sessionsFlow.collectAsState()
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Historique") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Retour") }
                }
            )
        }
    ) { pad ->
        if (sessions.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Aucune session pour le moment.")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .padding(12.dp)
            ) {
                items(sessions) { s ->
                    SessionCard(s)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
fun SessionCard(s: SessionResult) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date(s.timestamp))
            Text("Session du $date", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text("Essais: ${s.trials}")
            val pctOfMin = (s.thresholdNorm * 100f)
            Text("Seuil estimé (relatif): ${"%.2f".format(pctOfMin)} % du côté court")
            Spacer(Modifier.height(4.dp))
            Text(
                "Note : estimation basée sur une méthode « staircase » 3-contre-1. " +
                        "Usage informatif uniquement."
            )
        }
    }
}