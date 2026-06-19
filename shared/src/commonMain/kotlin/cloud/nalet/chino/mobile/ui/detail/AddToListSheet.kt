package cloud.nalet.chino.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cloud.nalet.chino.mobile.data.api.Watchlist
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus

/**
 * Add-to-list picker for the detail page. A scrim + bottom-anchored #161B22
 * card (matching AccountPicker's hand-rolled dialog idiom) listing every
 * watchlist with a checkbox — checked when the item is in that list. Toggling
 * a row calls back into the repository (PUT/DELETE items). A trailing
 * "New list…" affordance reveals an inline name field that creates a list and
 * adds the item to it.
 *
 * @param checkedListIds the lists the item currently belongs to (seeds the
 *        checkmarks). Read live from the repository so optimistic toggles
 *        reflect immediately.
 */
@Composable
fun AddToListSheet(
    lists: List<Watchlist>,
    checkedListIds: Set<String>,
    createError: String?,
    onToggle: (listId: String, checked: Boolean) -> Unit,
    onCreateAndAdd: (name: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var addingNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(Color(0xFF161B22))
                .border(
                    width = 1.dp,
                    color = Color(0xFF30363D),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                )
                // Swallow card clicks so a tap inside doesn't dismiss.
                .clickable(enabled = false) {}
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Add to list",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(lists, key = { it.id }) { list ->
                    ListCheckRow(
                        name = list.name,
                        checked = list.id in checkedListIds,
                        onToggle = { onToggle(list.id, list.id !in checkedListIds) },
                    )
                }
            }
            if (addingNew) {
                NewListInlineField(
                    value = newName,
                    onValueChange = { if (it.length <= 60) newName = it },
                    errorText = createError,
                    onSubmit = {
                        val trimmed = newName.trim()
                        if (trimmed.isNotEmpty()) {
                            onCreateAndAdd(trimmed)
                            newName = ""
                            addingNew = false
                        }
                    },
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { addingNew = true }
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Lucide.Plus,
                        contentDescription = null,
                        tint = Color(0xFF58A6FF),
                        modifier = Modifier.size(20.dp),
                    )
                    Text(text = "New list…", color = Color(0xFF58A6FF), fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun ListCheckRow(name: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) Color(0xFF2EA043) else Color.Transparent)
                .border(
                    width = 1.5.dp,
                    color = if (checked) Color(0xFF2EA043) else Color(0xFF30363D),
                    shape = RoundedCornerShape(6.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(
                    imageVector = Lucide.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = name,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NewListInlineField(
    value: String,
    onValueChange: (String) -> Unit,
    errorText: String?,
    onSubmit: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = { Text("List name", color = Color(0xFF8B949E)) },
            textStyle = LocalTextStyle.current.copy(color = Color.White, fontSize = 16.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF58A6FF))
                        .clickable(onClick = onSubmit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = "Create and add",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF0D1117),
                unfocusedContainerColor = Color(0xFF0D1117),
                focusedIndicatorColor = Color(0xFF58A6FF),
                unfocusedIndicatorColor = Color(0xFF30363D),
                cursorColor = Color(0xFF58A6FF),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        errorText?.let {
            Text(text = it, color = Color(0xFFF85149), fontSize = 13.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
