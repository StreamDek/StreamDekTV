package com.streamdek.tv.nativeapp.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.PersonDetail
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CastDetailScreen(
    repository: StreamDekRepository,
    personId: String,
    entryFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    var person by remember(personId) { mutableStateOf<PersonDetail?>(null) }
    var loading by remember(personId) { mutableStateOf(true) }
    var error by remember(personId) { mutableStateOf<String?>(null) }
    var reloadToken by remember(personId) { mutableStateOf(0) }

    BackHandler(onBack = onBack)
    LaunchedEffect(personId, reloadToken) {
        loading = true
        error = null
        person = runCatching { repository.fetchPerson(personId) }
            .onFailure { error = it.message ?: "Could not load cast details" }
            .getOrNull()
        if (person == null && error == null) error = "Could not load cast details"
        loading = false
    }
    LaunchedEffect(person?.id, error) {
        if (loading) return@LaunchedEffect
        delay(120)
        runCatching { entryFocusRequester.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    listOf(Color(0xFF121A22), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background),
                ),
            ),
        )
        when {
            loading -> CastDetailSkeleton()
            error != null -> DetailError(error.orEmpty(), onRetry = { reloadToken += 1 }, onBack = onBack)
            person != null -> CastDetailContent(person!!, entryFocusRequester, onBack, onOpenDetail)
        }
    }
}

@Composable
private fun CastDetailContent(
    person: PersonDetail,
    entryFocusRequester: FocusRequester,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    val biographyScroll = rememberScrollState()
    val biographyRequester = remember(person.id) { FocusRequester() }
    val knownForRequester = remember(person.id) { FocusRequester() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val biography = person.biography?.takeIf { it.isNotBlank() }
    var biographyFocused by remember(person.id) { mutableStateOf(false) }
    LaunchedEffect(person.id) { biographyScroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().padding(start = 104.dp, top = 40.dp, end = TvSpacing.ScreenHorizontal, bottom = 34.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
            Box(
                Modifier
                    .size(200.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                person.photo?.takeIf { it.isNotBlank() }?.let {
                    AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    Text(
                        person.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    // Outside the biography scroll: focusing this must never drag the biography
                    // to its bottom and hide the opening paragraph.
                    Button(
                        onClick = onBack,
                        modifier = Modifier
                            .focusRequester(entryFocusRequester)
                            .focusProperties {
                                down = when {
                                    biography != null -> biographyRequester
                                    person.popularWorks.isNotEmpty() -> knownForRequester
                                    else -> FocusRequester.Default
                                }
                            },
                    ) { Text("Back") }
                }
                listOfNotNull(person.knownFor, person.birthday, person.placeOfBirth)
                    .filter(String::isNotBlank)
                    .takeIf { it.isNotEmpty() }
                    ?.let { metadata ->
                        Text(
                            metadata.joinToString("  •  "),
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.68f),
                            maxLines = 2,
                        )
                    }
                biography?.let {
                    Text(
                        "Biography",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .focusRequester(biographyRequester)
                            .focusProperties {
                                up = entryFocusRequester
                                if (person.popularWorks.isNotEmpty()) down = knownForRequester
                            }
                            .onFocusChanged { biographyFocused = it.isFocused }
                            .onPreviewKeyEvent { event ->
                                if (!biographyFocused || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionDown -> {
                                        if (biographyScroll.value >= biographyScroll.maxValue) return@onPreviewKeyEvent false
                                        scope.launch { biographyScroll.animateScrollBy(120f) }
                                        true
                                    }
                                    Key.DirectionUp -> {
                                        if (biographyScroll.value <= 0) return@onPreviewKeyEvent false
                                        scope.launch { biographyScroll.animateScrollBy(-120f) }
                                        true
                                    }
                                    else -> false
                                }
                            }
                            .focusable()
                            .background(
                                if (biographyFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                else Color.Transparent,
                                AppCardShape,
                            )
                            .verticalScroll(biographyScroll)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            it,
                            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 8.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.84f),
                        )
                    }
                }
            }
        }
        if (person.popularWorks.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // This section shares the outer column's leading edge with the portrait.
                Text(
                    "Known For",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onBackground,
                )
                LazyRow(
                    modifier = Modifier.focusGroup(),
                    contentPadding = PaddingValues(end = TvSpacing.ScreenHorizontal),
                    horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                ) {
                    itemsIndexed(person.popularWorks, key = { _, item -> "${item.type}:${item.id}" }) { index, item ->
                        PremiumMediaCard(
                            item = item,
                            variant = TvMediaCardVariant.Poster,
                            showLabels = false,
                            modifier = Modifier
                                .width(106.dp)
                                .height(159.dp)
                                .then(if (index == 0) Modifier.focusRequester(knownForRequester) else Modifier)
                                .focusProperties { up = biographyRequester.takeIf { biography != null } ?: entryFocusRequester },
                            onClick = { onOpenDetail(item.type, item.detailLookupId()) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CastDetailSkeleton() {
    Row(
        Modifier.fillMaxSize().padding(start = 104.dp, top = 72.dp, end = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(34.dp),
    ) {
        TvSkeletonBox(Modifier.size(200.dp).clip(CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TvSkeletonBox(Modifier.width(360.dp).height(44.dp))
            TvSkeletonBox(Modifier.width(220.dp).height(18.dp))
            Spacer(Modifier.height(8.dp))
            repeat(5) { TvSkeletonBox(Modifier.fillMaxWidth().height(16.dp)) }
        }
    }
}
