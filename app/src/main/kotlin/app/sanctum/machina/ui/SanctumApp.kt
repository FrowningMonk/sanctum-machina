package app.sanctum.machina.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.sanctum.machina.ui.chat.ChatScreen
import app.sanctum.machina.ui.modelmanager.ModelManagerScreen

@Composable
fun SanctumApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "model_manager") {
        composable("model_manager") {
            ModelManagerScreen(
                onLoad = { modelName ->
                    navController.navigate("chat/${Uri.encode(modelName)}")
                },
            )
        }
        composable(
            route = "chat/{modelName}",
            arguments = listOf(navArgument("modelName") { type = NavType.StringType }),
        ) { entry ->
            val raw = entry.arguments?.getString("modelName") ?: return@composable
            val modelName = Uri.decode(raw)
            ChatScreen(
                modelName = modelName,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
