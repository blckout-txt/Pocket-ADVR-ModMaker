package com.advr.luaeditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.advr.luaeditor.lua.Completion
import com.advr.luaeditor.lua.CompletionKind
import com.advr.luaeditor.lua.CompletionResult
import com.advr.luaeditor.lua.SignatureInfo

/** Short glyph shown on a completion chip, so kinds are distinguishable at a glance. */
private fun glyph(kind: CompletionKind): String = when (kind) {
    CompletionKind.FIELD -> "▪"
    CompletionKind.METHOD -> "ƒ"
    CompletionKind.EVENT -> "⚡"
    CompletionKind.LOCAL -> "L"
    CompletionKind.PARAM -> "p"
    CompletionKind.LOOP_VAR -> "i"
    CompletionKind.FILE_GLOBAL -> "G"
    CompletionKind.FILE_FUNCTION -> "ƒ"
    CompletionKind.SELF_GLOBAL -> "★"
    CompletionKind.API_GLOBAL -> "◆"
    CompletionKind.KEYWORD -> "K"
    CompletionKind.SNIPPET -> "❏"
    CompletionKind.CLASS -> "C"
}

@Composable
private fun kindColor(kind: CompletionKind): Color {
    val c = LocalCodeColors.current
    return when (kind) {
        CompletionKind.FIELD -> c.member
        CompletionKind.METHOD, CompletionKind.FILE_FUNCTION -> c.call
        CompletionKind.EVENT -> c.annotation
        CompletionKind.LOCAL, CompletionKind.PARAM, CompletionKind.LOOP_VAR -> c.plain
        CompletionKind.FILE_GLOBAL -> c.apiGlobal
        CompletionKind.SELF_GLOBAL -> c.selfGlobal
        CompletionKind.API_GLOBAL -> c.apiGlobal
        CompletionKind.KEYWORD -> c.keyword
        CompletionKind.SNIPPET -> c.number
        CompletionKind.CLASS -> c.annotation
    }
}

/**
 * A horizontally scrolling strip of suggestions sitting directly above the keyboard, which is the
 * only place on a phone where a completion list is genuinely reachable with a thumb.
 */
@Composable
fun SuggestionStrip(
    result: CompletionResult,
    expanded: Boolean,
    onPick: (Completion) -> Unit,
    onToggleExpand: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (result.isEmpty) return
    val colors = LocalCodeColors.current
    val listState = rememberLazyListState()
    LaunchedEffect(result) { listState.scrollToItem(0) }

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = result.header,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).width(150.dp),
            )
            LazyRow(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items(result.items.take(40), key = { it.label + it.kind }) { item ->
                    SuggestionChip(item, onPick)
                }
            }
            IconButton(onClick = onToggleExpand, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = if (expanded) "Collapse suggestions" else "Show all suggestions",
                    tint = colors.gutterActive,
                )
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Dismiss suggestions", tint = colors.gutterActive)
            }
        }
        if (expanded) {
            ExpandedCompletionList(result, onPick)
        }
    }
}

@Composable
private fun SuggestionChip(item: Completion, onPick: (Completion) -> Unit) {
    val colors = LocalCodeColors.current
    val tint = kindColor(item.kind)
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .clickable { onPick(item) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph(item.kind), color = tint, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(6.dp))
        Text(
            item.label,
            color = colors.plain,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
        )
    }
}

@Composable
private fun ExpandedCompletionList(result: CompletionResult, onPick: (Completion) -> Unit) {
    val colors = LocalCodeColors.current
    LazyColumn(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        itemsIndexed(result.items) { _, item ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(item) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    glyph(item.kind),
                    color = kindColor(item.kind),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 2.dp).width(20.dp),
                )
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            item.label,
                            color = colors.plain,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                        )
                        if (item.badge.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                item.badge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (item.detail.isNotEmpty()) {
                        Text(
                            item.detail,
                            color = colors.gutterActive,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (item.doc.isNotEmpty()) {
                        Text(
                            item.doc,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** `Method(a: X, b: Y)` with the parameter the caret is on highlighted. */
@Composable
fun SignatureStrip(sig: SignatureInfo, modifier: Modifier = Modifier) {
    val colors = LocalCodeColors.current
    val text = buildAnnotatedString {
        val open = sig.label.indexOf('(')
        if (open < 0) {
            append(sig.label)
        } else {
            withStyle(SpanStyle(color = colors.call)) { append(sig.label.substring(0, open)) }
            append("(")
            sig.params.forEachIndexed { i, p ->
                if (i > 0) append(", ")
                val style = if (i == sig.activeParam)
                    SpanStyle(color = colors.selfGlobal, fontWeight = FontWeight.Bold)
                else SpanStyle(color = colors.gutterActive)
                withStyle(style) { append("${p.name}: ${p.type.replace('│', '|')}") }
            }
            append(")")
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (sig.overloadCount > 1) {
            Text(
                "${sig.overloadIndex + 1}/${sig.overloadCount}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
