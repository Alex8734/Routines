package at.resch.routines

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import at.resch.routines.core.MacroForegroundService
import at.resch.routines.data.AppDatabase
import at.resch.routines.data.MacroRepository
import at.resch.routines.ui.screen.MacroDashboardScreen
import at.resch.routines.ui.screen.MacroEditorScreen
import at.resch.routines.ui.theme.RoutinesTheme
import at.resch.routines.ui.viewmodel.MacroDashboardViewModel
import at.resch.routines.ui.viewmodel.MacroEditorViewModel

/**
 * Routen-Konstanten für die Navigation.
 * Konvention: Kleinschrift-Snake-Case, optionale Query-Args via "?key={key}".
 */
private object Routes {
    const val DASHBOARD = "dashboard"

    /** Editor-Route mit optionaler Makro-ID. Kein Argument = neues Makro. */
    const val EDITOR = "editor"
    const val ARG_MACRO_ID = "macroId"
    const val EDITOR_WITH_ARG = "editor?macroId={macroId}"

    /** Erstellt die Editor-Route mit einer konkreten Makro-ID. */
    fun editorForId(macroId: String) = "editor?macroId=$macroId"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Manuelles DI: Repository aus der Room-Singleton-Instanz bauen.
        // Kein Hilt — einfach, testbar, ohne Overhead.
        val repository = MacroRepository(
            AppDatabase.getInstance(applicationContext).macroDao()
        )

        setContent {
            RoutinesTheme {
                AppStartup(repository = repository)
            }
        }
    }
}

/**
 * App-Einstiegspunkt: kümmert sich einmalig um Berechtigungen und Service-Start,
 * bevor der NavHost gerendert wird.
 *
 * - Android 13+ (TIRAMISU): fordert POST_NOTIFICATIONS zur Laufzeit an, falls
 *   die Berechtigung noch nicht erteilt wurde.
 * - Startet [MacroForegroundService] nach der Berechtigungsanfrage, unabhängig
 *   vom Ergebnis (der Service verwaltet seine Notification selbst).
 * - Schutz gegen Mehrfach-Start: [LaunchedEffect] mit Unit-Key läuft nur einmal
 *   pro Composition, d. h. genau einmal pro App-Start.
 */
@Composable
private fun AppStartup(repository: MacroRepository) {
    val context = LocalContext.current

    // Launcher für die Runtime-Berechtigungsanfrage (POST_NOTIFICATIONS).
    // Das Ergebnis wird hier nicht ausgewertet — der Service startet in jedem Fall,
    // da er die Notification intern absichert.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Ergebnis wird ignoriert — Service läuft unabhängig vom Grant-Status */ }

    // Einmalige Initialisierung beim ersten Eintreten in die Composition.
    LaunchedEffect(Unit) {
        // POST_NOTIFICATIONS ist erst ab API 33 (TIRAMISU) eine Runtime-Berechtigung.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val already = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!already) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Foreground-Service starten (Boot-Pfad bleibt unberührt — kein emitStartup).
        MacroForegroundService.start(context)
    }

    RoutinesNavHost(repository = repository)
}

/**
 * Zentraler NavHost für die App.
 * Verwaltet alle Routen und leitet Repository an die jeweiligen ViewModel-Factories weiter.
 *
 * Routen:
 * - [Routes.DASHBOARD] — Makro-Liste (Startseite)
 * - [Routes.EDITOR_WITH_ARG] — Editor; `macroId` optional (fehlt → neues Makro)
 */
@Composable
private fun RoutinesNavHost(repository: MacroRepository) {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.DASHBOARD
    ) {

        // Dashboard: Liste aller Makros
        composable(route = Routes.DASHBOARD) {
            val dashboardFactory = MacroDashboardViewModel.Factory(repository)
            val dashboardViewModel: MacroDashboardViewModel = viewModel(factory = dashboardFactory)

            MacroDashboardScreen(
                viewModel = dashboardViewModel,
                onEditMacro = { macro ->
                    navController.navigate(Routes.editorForId(macro.id))
                },
                onNewMacro = {
                    navController.navigate(Routes.EDITOR)
                }
            )
        }

        // Editor: vorhandenes Makro bearbeiten oder neues anlegen
        composable(
            route = Routes.EDITOR_WITH_ARG,
            arguments = listOf(
                navArgument(Routes.ARG_MACRO_ID) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val macroId = backStackEntry.arguments?.getString(Routes.ARG_MACRO_ID)
            val editorFactory = MacroEditorViewModel.Factory(repository, macroId)
            val editorViewModel: MacroEditorViewModel = viewModel(factory = editorFactory)

            MacroEditorScreen(
                viewModel = editorViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
