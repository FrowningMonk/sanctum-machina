package app.sanctum.machina.ui

import android.net.Uri
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sanctum.machina.ui.about.AboutScreen
import app.sanctum.machina.ui.chat.ChatScreen
import app.sanctum.machina.ui.diagnostics.DiagnosticsScreen
import app.sanctum.machina.ui.drawer.DrawerContent
import app.sanctum.machina.ui.home.HomeScreen
import app.sanctum.machina.ui.modelmanager.ModelManagerScreen
import app.sanctum.machina.ui.projects.NAV_ARG_PROJECT_ID
import app.sanctum.machina.ui.projects.ProjectCreateScreen
import app.sanctum.machina.ui.projects.ProjectDetailScreen
import app.sanctum.machina.ui.projects.ProjectSettingsScreen
import app.sanctum.machina.ui.theme.SanctumTheme
import kotlinx.coroutines.launch

@Composable
fun SanctumApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()
    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentChatId: Long? = currentBackStackEntry
        ?.takeIf { it.destination.route == "chat/{chatId}" }
        ?.arguments
        ?.takeIf { it.containsKey("chatId") }
        ?.getLong("chatId")

    SanctumTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                DrawerContent(
                    currentChatId = currentChatId,
                    onChatClick = { chatId ->
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("chat/$chatId")
                    },
                    onNewChat = {
                        // Drawer "Новый чат" opens a persistent draft with the model picker
                        // (TopAppBarState.Draft, AC-U7). The Home "Начать быстрый чат" button
                        // is the incognito entry point that stays on chat/quick.
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("chat/draft")
                    },
                    onNavigateToHome = {
                        // Decision 14: popUpTo("home") with inclusive = false +
                        // launchSingleTop reuses the existing Home entry instead
                        // of stacking new copies on repeated taps.
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("home") {
                            popUpTo("home") { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onProjectClick = { projectId ->
                        // Route `project/{projectId}` registered in Task 9.
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("project/$projectId")
                    },
                    onNewProject = {
                        // Task 9 entry-point. Sentinel projectId=0L — the Create screen
                        // reads the route arg only for the popUpTo target at navigation
                        // time; the new project's real id arrives via `onCreated`.
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("project/0/create")
                    },
                    onNavigateToModelManager = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("model_manager")
                    },
                    onOpenModelManager = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("model_manager")
                    },
                    onNavigateToDiagnostics = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("diagnostics")
                    },
                    onNavigateToAbout = {
                        coroutineScope.launch { drawerState.close() }
                        navController.navigate("about")
                    },
                    onPopCurrentChat = {
                        // Current chat was deleted by the drawer while open — pop
                        // the ChatScreen off the back stack (AC-U3) so the user
                        // is not left staring at a now-empty Room flow.
                        if (navController.currentDestination?.route == "chat/{chatId}") {
                            navController.popBackStack()
                        }
                    },
                )
            },
        ) {
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    HomeScreen(
                        onNewQuickChat = { navController.navigate("chat/quick") },
                        onOpenModelManager = { navController.navigate("model_manager") },
                        onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                    )
                }
                composable(
                    route = "chat/quick?modelId={modelId}",
                    arguments = listOf(
                        navArgument("modelId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        // Task 8: constant "kind" marker so ChatViewModel can distinguish
                        // Quick from Draft when no chatId nav arg is present. Default values
                        // are injected into SavedStateHandle without appearing in the route.
                        navArgument("kind") {
                            type = NavType.StringType
                            defaultValue = "quick"
                        },
                    ),
                ) {
                    ChatScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToPersistent = { chatId ->
                            navController.navigate("chat/$chatId") {
                                // Draft→Persistent atomic handover (AC-P7): remove the draft
                                // route so Back from the persistent chat returns to Home.
                                popUpTo("chat/draft") { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    route = "chat/draft",
                    arguments = listOf(
                        navArgument("kind") {
                            type = NavType.StringType
                            defaultValue = "draft"
                        },
                    ),
                ) {
                    ChatScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToPersistent = { chatId ->
                            navController.navigate("chat/$chatId") {
                                popUpTo("chat/draft") { inclusive = true }
                            }
                        },
                    )
                }
                // Phase 4 Task 9 — project routes MUST be registered before the
                // `chat/{modelName}` StringType tombstone. NavController matches routes in
                // registration order; placing these after the tombstone would have any
                // `project/...` URL fall into the catch-all and redirect to home.
                composable(
                    route = "project/{projectId}/create",
                    arguments = listOf(
                        navArgument(NAV_ARG_PROJECT_ID) { type = NavType.LongType },
                    ),
                ) {
                    ProjectCreateScreen(
                        onCreated = { newProjectId ->
                            navController.navigate("project/$newProjectId") {
                                // The sentinel-id create route (`project/0/create`) drops out
                                // of the back stack so Back from detail returns to the caller.
                                popUpTo("project/{projectId}/create") { inclusive = true }
                            }
                        },
                        onCancel = { navController.popBackStack() },
                    )
                }
                composable(
                    route = "project/{projectId}",
                    arguments = listOf(
                        navArgument(NAV_ARG_PROJECT_ID) { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getLong(NAV_ARG_PROJECT_ID)
                    ProjectDetailScreen(
                        onBack = { navController.popBackStack() },
                        onOpenSettings = {
                            if (projectId != null) {
                                navController.navigate("project/$projectId/settings")
                            }
                        },
                        onOpenChat = { chatId -> navController.navigate("chat/$chatId") },
                        onNewChat = { _ ->
                            // Project chat creation route lands in Task 11; for now route into
                            // the standard draft entry — ChatViewModel will resolve `project_id`
                            // from the parent surface when that task lands.
                            navController.navigate("chat/draft")
                        },
                        onOpenModelManager = { navController.navigate("model_manager") },
                    )
                }
                composable(
                    route = "project/{projectId}/settings",
                    arguments = listOf(
                        navArgument(NAV_ARG_PROJECT_ID) { type = NavType.LongType },
                    ),
                ) {
                    ProjectSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                // Registration order matters: `chat/{chatId}` must be declared BEFORE the
                // `chat/{modelName}` tombstone below. Navigation matches routes in registration
                // order; a non-numeric segment like `chat/some-model` fails `NavType.LongType`
                // parsing and falls through to the StringType tombstone → redirect to home.
                composable(
                    route = "chat/{chatId}",
                    arguments = listOf(
                        navArgument("chatId") { type = NavType.LongType },
                    ),
                ) {
                    ChatScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToPersistent = { /* persistent chats don't re-navigate */ },
                    )
                }
                // TOMBSTONE: deprecated in Task 7, removed in Task 8. The old string-based
                // chat/{modelName} route is kept registered to prevent route-not-found crashes
                // if a deep link lands on it during the same working session where Task 7 ships
                // without Task 8. On entry, redirect to home and clear the backstack.
                composable(
                    route = "chat/{modelName}",
                    arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
                ) {
                    LaunchedEffect(Unit) {
                        navController.navigate("home") {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                }
                composable("model_manager") {
                    ModelManagerScreen(
                        onLoad = { modelId ->
                            // Model IDs are HF paths like "litert-community/gemma-..."; slashes
                            // would break the route match without percent-encoding.
                            navController.navigate("chat/quick?modelId=${Uri.encode(modelId)}")
                        },
                        onAbout = { navController.navigate("about") },
                    )
                }
                composable("about") {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
                composable("diagnostics") {
                    DiagnosticsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

