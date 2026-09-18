package pro.freedoom.poweremote.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pro.freedoom.poweremote.shared.Cmd
import pro.freedoom.poweremote.shared.LibListing
import pro.freedoom.poweremote.shared.PlayerState

/**
 * Обзор папок библиотеки Poweramp. Данные приходят с плеера по запросу:
 * открыли папку — ушёл BROWSE, пришёл "list".
 */
@Composable
fun LibraryScreen(state: PlayerState?, live: Boolean, haptic: () -> Unit, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val listing by LinkBus.listing.collectAsStateWithLifecycle()
    val loading by LinkBus.libLoading.collectAsStateWithLifecycle()
    val canBrowse = state?.canBrowse == true

    // Стек id папок для кнопки «назад».
    val stack = remember { mutableStateListOf<Long>() }
    var current by remember { mutableLongStateOf(LibListing.ROOT_ID) }

    fun open(id: Long) {
        if (id != current) stack.add(current)
        current = id
        LinkService.browse(id)
    }

    fun up() {
        if (stack.isNotEmpty()) {
            current = stack.removeAt(stack.lastIndex)
            LinkService.browse(current)
        } else onBack()
    }

    BackHandler { up() }

    LaunchedEffect(canBrowse, live) {
        if (canBrowse && live) LinkService.browse(current)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { up() }) { Icon(Icons.Filled.ArrowBack, "Назад") }
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        listing == null || listing?.folderId == LibListing.ROOT_ID -> "Папки"
                        else -> listing?.name?.ifEmpty { "Папка" } ?: "Папка"
                    },
                    fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                val l = listing
                if (l != null && l.folderId == current && l.error.isEmpty()) {
                    Text(
                        listOfNotNull(
                            l.folders.size.takeIf { it > 0 }?.let { "$it папок" },
                            l.files.size.takeIf { it > 0 }?.let { "$it треков" }
                        ).joinToString(" · ").ifEmpty { "Пусто" },
                        fontSize = 12.sp, color = cs.onSurfaceVariant
                    )
                }
            }
            if (current != LibListing.ROOT_ID && canBrowse) {
                FilledTonalButton(
                    onClick = { haptic(); LinkService.send(Cmd.PLAY_FOLDER, current) },
                    enabled = live
                ) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Играть папку")
                }
            }
        }

        when {
            !live -> Center("Нет связи с плеером")
            !canBrowse -> NoAccess(live)
            loading && (listing == null || listing?.folderId != current) ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            listing?.error == "no_permission" -> NoAccess(live)
            listing?.error?.isNotEmpty() == true -> Center("Плеер не смог прочитать библиотеку")
            else -> {
                val l = listing ?: return@Column
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(l.folders, key = { "f${it.id}" }) { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { haptic(); open(f.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Folder, null, tint = cs.primary)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(f.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    listOfNotNull(
                                        f.subfolders.takeIf { it > 0 }?.let { "$it папок" },
                                        f.files.takeIf { it > 0 }?.let { "$it треков" }
                                    ).joinToString(" · "),
                                    fontSize = 12.sp, color = cs.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { haptic(); LinkService.send(Cmd.PLAY_FOLDER, f.id) }) {
                                Icon(Icons.Filled.PlayArrow, "Играть папку", tint = cs.onSurfaceVariant)
                            }
                        }
                    }
                    items(l.files, key = { "t${it.id}" }) { t ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { haptic(); LinkService.send(Cmd.PLAY_FILE, t.id, l.folderId) }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.MusicNote, null, tint = cs.onSurfaceVariant)
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (t.artist.isNotEmpty()) Text(
                                    t.artist, fontSize = 12.sp, color = cs.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (t.durationSec > 0) Text(
                                fmt(t.durationSec * 1000L), fontSize = 12.sp, color = cs.onSurfaceVariant
                            )
                        }
                    }
                    if (l.folders.isEmpty() && l.files.isEmpty()) {
                        item { Center("Папка пуста") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Center(text: String) {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NoAccess(live: Boolean) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Нужен доступ к библиотеке Poweramp", fontWeight = FontWeight.SemiBold)
        Text(
            "Нажмите кнопку — на экране плеера Poweramp покажет запрос. Подтвердите его там, " +
                    "затем вернитесь сюда. Это делается один раз.",
            fontSize = 13.sp, color = cs.onSurfaceVariant
        )
        FilledTonalButton(onClick = { LinkService.send(Cmd.ASK_DATA) }, enabled = live) {
            Text("Запросить доступ на плеере")
        }
    }
}
