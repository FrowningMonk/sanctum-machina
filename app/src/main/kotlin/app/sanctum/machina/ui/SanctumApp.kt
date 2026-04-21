package app.sanctum.machina.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sanctum.machina.ui.about.AboutScreen
import app.sanctum.machina.ui.home.HomeScreen
import app.sanctum.machina.ui.modelmanager.ModelManagerScreen
import app.sanctum.machina.ui.theme.SanctumTheme
import kotlinx.coroutines.launch

@Composable
fun SanctumApp() {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val coroutineScope = rememberCoroutineScope()

    SanctumTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                // Task 9 replaces this stub with the real DrawerContent composable.
                ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                    Box(modifier = Modifier.fillMaxHeight().padding(16.dp)) {
                        Text("Drawer — Task 9")
                    }
                }
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
                    // TODO(Task 10): rework ChatScreen signature for ChatIdentity.Quick
                    QuickChatPlaceholder()
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
                    // TODO(Task 10): rework ChatScreen signature for ChatIdentity.Draft
                    DraftChatPlaceholder()
                }
                composable(
                    route = "chat/{chatId}",
                    arguments = listOf(
                        navArgument("chatId") { type = NavType.LongType },
                    ),
                ) {
                    // TODO(Task 8): rework ChatScreen signature for ChatIdentity.Persistent
                    PersistentChatPlaceholder()
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
                            // Task 11 wires this to chat/quick?modelId={modelId} with proper
                            // state handoff; for Task 7 the navigate call is already correct —
                            // the route is registered above with an optional modelId arg.
                            navController.navigate("chat/quick?modelId=$modelId")
                        },
                        onAbout = { navController.navigate("about") },
                    )
                }
                composable("about") {
                    AboutScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

@Composable
private fun QuickChatPlaceholder() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Quick chat — Task 8")
    }
}

@Composable
private fun DraftChatPlaceholder() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Draft chat — Task 8")
    }
}

@Composable
private fun PersistentChatPlaceholder() {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Persistent chat — Task 8")
    }
}
