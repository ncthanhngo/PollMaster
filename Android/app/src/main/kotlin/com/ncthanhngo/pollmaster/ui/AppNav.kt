package com.ncthanhngo.pollmaster.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ncthanhngo.pollmaster.ui.editor.FillScreen
import com.ncthanhngo.pollmaster.ui.editor.OutlineScreen
import com.ncthanhngo.pollmaster.ui.editor.ReviewScreen
import com.ncthanhngo.pollmaster.ui.editor.RowFormScreen
import com.ncthanhngo.pollmaster.ui.editor.SectionScreen
import com.ncthanhngo.pollmaster.ui.templates.TemplateDetailScreen

private object Routes {
    const val MAIN = "main"
    const val TEMPLATE = "template/{id}/{version}"
    const val OUTLINE = "editor/{recordId}"
    const val SECTION = "editor/{recordId}/section/{sectionId}?focus={focus}"
    const val ROW = "editor/{recordId}/row/{tableId}/{rowId}?focus={focus}"
    const val REVIEW = "editor/{recordId}/review"
    const val FILL = "editor/{recordId}/fill"

    fun template(id: String, version: String) = "template/${enc(id)}/${enc(version)}"
    fun outline(recordId: String) = "editor/$recordId"
    fun section(recordId: String, sectionId: String, focus: String? = null) =
        "editor/$recordId/section/${enc(sectionId)}" + (focus?.let { "?focus=${enc(it)}" } ?: "")
    fun row(recordId: String, tableId: String, rowId: String, focus: String? = null) =
        "editor/$recordId/row/${enc(tableId)}/${enc(rowId)}" + (focus?.let { "?focus=${enc(it)}" } ?: "")
    fun review(recordId: String) = "editor/$recordId/review"
    fun fill(recordId: String) = "editor/$recordId/fill"

    private fun enc(s: String) = android.net.Uri.encode(s)
}

private val focusArg = navArgument("focus") {
    type = NavType.StringType
    nullable = true
    defaultValue = null
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) {
            MainScreen(
                onOpenRecord = { nav.navigate(Routes.outline(it)) },
                onOpenTemplate = { t -> nav.navigate(Routes.template(t.id, t.version)) },
            )
        }
        composable(Routes.TEMPLATE) { entry ->
            TemplateDetailScreen(
                id = entry.arguments?.getString("id").orEmpty(),
                version = entry.arguments?.getString("version").orEmpty(),
                onBack = { nav.popBackStack() },
                onRecordCreated = { nav.navigate(Routes.outline(it)) { popUpTo(Routes.MAIN) } },
            )
        }
        composable(Routes.OUTLINE) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            OutlineScreen(
                recordId = recordId,
                onBack = { nav.popBackStack() },
                onOpenSection = { section, focus -> nav.navigate(Routes.section(recordId, section, focus)) },
                onReview = { nav.navigate(Routes.review(recordId)) },
            )
        }
        composable(Routes.SECTION, arguments = listOf(focusArg)) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            SectionScreen(
                recordId = recordId,
                sectionId = entry.arguments?.getString("sectionId").orEmpty(),
                focus = entry.arguments?.getString("focus"),
                onBack = { nav.popBackStack() },
                onSwitchSection = { nav.navigate(Routes.section(recordId, it)) { popUpTo(Routes.OUTLINE) } },
                onOpenRow = { table, row -> nav.navigate(Routes.row(recordId, table, row)) },
                onReview = { nav.navigate(Routes.review(recordId)) },
            )
        }
        composable(Routes.ROW, arguments = listOf(focusArg)) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            RowFormScreen(
                recordId = recordId,
                tableId = entry.arguments?.getString("tableId").orEmpty(),
                rowId = entry.arguments?.getString("rowId").orEmpty(),
                focus = entry.arguments?.getString("focus"),
                onBack = { nav.popBackStack() },
                onNextRow = { table, row ->
                    nav.navigate(Routes.row(recordId, table, row)) { popUpTo(Routes.ROW) { inclusive = true } }
                },
                onOpenField = { section, field -> nav.navigate(Routes.section(recordId, section, field)) },
            )
        }
        composable(Routes.REVIEW) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            ReviewScreen(
                recordId = recordId,
                onBack = { nav.popBackStack() },
                onJumpField = { section, field -> nav.navigate(Routes.section(recordId, section, field)) },
                onJumpRow = { table, row, column -> nav.navigate(Routes.row(recordId, table, row, column)) },
                onFillOneByOne = { nav.navigate(Routes.fill(recordId)) },
            )
        }
        composable(Routes.FILL) { entry ->
            val recordId = entry.arguments?.getString("recordId").orEmpty()
            FillScreen(
                recordId = recordId,
                onBack = { nav.popBackStack() },
                onOpenField = { section, field -> nav.navigate(Routes.section(recordId, section, field)) },
                onOpenRow = { table, row, column -> nav.navigate(Routes.row(recordId, table, row, column)) },
            )
        }
    }
}
