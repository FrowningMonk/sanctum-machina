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
import app.sanctum.machina.ui.chat.ChatViewModel
import app.sanctum.machina.ui.diagnostics.DiagnosticsScreen
import app.sanctum.machina.ui.drawer.DrawerContent
import app.sanctum.machina.ui.home.HomeScreen
import app.sanctum.machina.ui.modelmanager.ModelManagerScreen
import app.sanctum.machina.ui.projects.NAV_ARG_PROJECT_ID
import app.sanctum.machina.ui.projects.ProjectChunksScreen
import app.sanctum.machina.ui.projects.ProjectCreateScreen
import app.sanctum.machina.ui.projects.ProjectDetailScreen
import app.sanctum.machina.ui.projects.ProjectSettingsScreen
import app.sanctum.machina.ui.theme.SanctumTheme
import kotlinx.coroutines.launch

/**
 * Phase 4 Task 19 — single source of truth for the draft route template.
 * Used by the `composable(...)` declaration and the two `popUpTo(...)` calls
 * that need to match the registered template verbatim, so they cannot drift
 * apart on a future rename.
 */
private const val ROUTE_CHAT_DRAFT = "chat/draft?projectId={projectId}"

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
                        // Task 9 entry-point. Concrete `projectId=0L` is a sentinel that
                        // matches the parameterised route template `project/{projectId}/create`;
                        // the Create composable's `popUpTo` uses the template form, so the
                        // concrete value never needs to equal a real project id. The new
                        // project's real id arrives via the `onCreated` callback inside the
                        // create composable.
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
                                // popUpTo argument must match the registered route template,
                                // which carries the `?projectId={projectId}` query arg after
                                // Phase 4 Task 19.
                                popUpTo(ROUTE_CHAT_DRAFT) { inclusive = true }
                            }
                        },
                    )
                }
                composable(
                    // Phase 4 Task 19: optional `?projectId={id}` query arg. Drawer
                    // «+ Новый чат» keeps using the parameter-less form — the
                    // route still matches because the projectId default below
                    // is the -1L sentinel that `ChatViewModel` reads back as
                    // `null` (Long primitives reject `nullable = true` in
                    // AndroidX Nav). `ProjectDetailScreen` lands on
                    // `chat/draft?projectId=$projectId` so the VM's Draft
                    // identity carries the project linkage forward into
                    // `commitDraftChat` and the new `chats.project_id` lands
                    // non-null (US-AC3 invariant).
                    route = ROUTE_CHAT_DRAFT,
                    arguments = listOf(
                        navArgument(ChatViewModel.NAV_ARG_KIND) {
                            type = NavType.StringType
                            defaultValue = ChatViewModel.KIND_DRAFT
                        },
                        navArgument(ChatViewModel.NAV_ARG_PROJECT_ID) {
                            type = NavType.LongType
                            defaultValue = ChatViewModel.NO_PROJECT_ID_SENTINEL
                        },
                    ),
                ) {
                    ChatScreen(
                        onBack = { navController.popBackStack() },
                        onNavigateToPersistent = { chatId ->
                            navController.navigate("chat/$chatId") {
                                popUpTo(ROUTE_CHAT_DRAFT) { inclusive = true }
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
                            // Phase 4 Task 19: route `chat/draft?projectId={id}`. The optional
                            // `defaultModelId` callback arg is intentionally ignored — the
                            // model is still picked from the Draft model picker (project chats
                            // inherit the user's chat-tier choice; the project's `defaultModelId`
                            // is reserved for a future "pin project model" feature, not Phase 4).
                            // `projectId` is non-null here by construction of the project
                            // route, but guard defensively so a navigation-arg corruption
                            // falls back to the plain Drawer flow rather than crashing.
                            if (projectId != null) {
                                navController.navigate("chat/draft?projectId=$projectId")
                            } else {
                                navController.navigate("chat/draft")
                            }
                        },
                        onOpenModelManager = { navController.navigate("model_manager") },
                    )
                }
                composable(
                    route = "project/{projectId}/settings",
                    arguments = listOf(
                        navArgument(NAV_ARG_PROJECT_ID) { type = NavType.LongType },
                    ),
                ) { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getLong(NAV_ARG_PROJECT_ID)
                    ProjectSettingsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenChunks = {
                            if (projectId != null) {
                                navController.navigate("project/$projectId/chunks")
                            }
                        },
                    )
                }
                composable(
                    route = "project/{projectId}/chunks",
                    arguments = listOf(
                        navArgument(NAV_ARG_PROJECT_ID) { type = NavType.LongType },
                    ),
                ) {
                    ProjectChunksScreen(onBack = { navController.popBackStack() })
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

