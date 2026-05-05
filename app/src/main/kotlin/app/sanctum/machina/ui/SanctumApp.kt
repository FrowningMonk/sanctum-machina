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

