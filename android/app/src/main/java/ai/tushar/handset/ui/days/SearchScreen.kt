package ai.tushar.handset.ui.days

import ai.tushar.handset.R
import ai.tushar.handset.ui.IconButton
import ai.tushar.handset.ui.SectionLabel
import ai.tushar.handset.ui.theme.AppSize
import ai.tushar.handset.ui.theme.AppTheme
import ai.tushar.handset.ui.theme.AppType
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private const val SEARCH_DELAY_MS = 150L

/** Search over everything stored, grouped by day, with the words found in bold */
@Composable
fun SearchScreen(query: String, onQuery: (String) -> Unit, repository: DayRepository, selected: MomentId?, onBack: () -> Unit, onOpen: (Moment) -> Unit) {
    val colors = AppTheme.colors
    val format = LocalFormat.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var hits by remember { mutableStateOf<List<SearchHit>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = null
            return@LaunchedEffect
        }
        delay(SEARCH_DELAY_MS)
        hits = repository.search(query.trim())
    }
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = AppSize.headerHeight).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(R.drawable.ph_arrow_left, "Back", onClick = onBack)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = AppType.body.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search your days" },
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = AppSize.field).background(colors.surface, CircleShape).padding(start = 16.dp, end = 0.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (query.isEmpty()) Text("Search your days", style = AppType.body, color = colors.text3)
                            field()
                        }
                        if (query.isNotEmpty()) IconButton(R.drawable.ph_x, "Clear search") { onQuery("") } else Spacer(Modifier.size(AppSize.touch))
                    }
                },
            )
        }
        val found = hits
        when {
            query.isBlank() -> Hint("Finds words in the screens you read and the notifications you got.")
            found == null -> Unit
            found.isEmpty() -> Hint("Nothing matches “${query.trim()}”.")
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = AppSize.pageX, end = AppSize.pageX, bottom = 32.dp)) {
                found.groupBy { format.dayOf(it.moment.at) }.forEach { (day, dayHits) ->
                    item(key = "d$day") { SectionLabel(format.longDay(day), top = 20.dp) }
                    items(dayHits, key = { it.moment.id.value }) { hit ->
                        MomentRow(hit.moment, format.time(hit.moment.at), selected = hit.moment.id == selected, text = hit.snippet, matches = hit.matches) { onOpen(hit.moment) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = AppType.rowText, color = AppTheme.colors.text3, modifier = Modifier.padding(horizontal = AppSize.pageX, vertical = 20.dp))
}
