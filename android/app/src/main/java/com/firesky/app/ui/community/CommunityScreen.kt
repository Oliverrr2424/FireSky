package com.firesky.app.ui.community

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.firesky.app.R
import com.firesky.app.ui.LucideIcon
import com.firesky.app.ui.Pill
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary

/** One shared sky photo. Sample posts for now; posts from people arrive once sharing is built. */
data class CommunityPost(
    val id: Int,
    @DrawableRes val photo: Int,
    val width: Int,
    val height: Int,
    val place: String,
    val mode: String,
    val author: String,
) {
    val aspect: Float get() = width.toFloat() / height
}

val SamplePosts = listOf(
    CommunityPost(1, R.drawable.community_demo_01, 900, 1200, "Toronto Waterfront, ON", "sunset", "Ava"),
    CommunityPost(2, R.drawable.community_demo_02, 900, 1000, "Blue Mountain, ON", "sunrise", "Marcus"),
    CommunityPost(3, R.drawable.community_demo_03, 900, 1350, "Santa Monica Pier, CA", "sunset", "Priya"),
    CommunityPost(4, R.drawable.community_demo_04, 1000, 740, "Hamilton Harbour, ON", "sunrise", "Leo"),
    CommunityPost(5, R.drawable.community_demo_05, 900, 900, "Vancouver Seawall, BC", "sunset", "Noor"),
    CommunityPost(6, R.drawable.community_demo_06, 840, 1260, "Muskoka, ON", "sunset", "Ethan"),
    CommunityPost(7, R.drawable.community_demo_07, 1000, 800, "Sedona, AZ", "sunset", "Mia"),
    CommunityPost(8, R.drawable.community_demo_08, 900, 1100, "Chicago Lakefront, IL", "sunrise", "Jonah"),
    CommunityPost(9, R.drawable.community_demo_09, 900, 1200, "Banff, AB", "sunset", "Chloe"),
    CommunityPost(10, R.drawable.community_demo_10, 1000, 700, "Old Montreal, QC", "sunrise", "Sam"),
    CommunityPost(11, R.drawable.community_demo_11, 860, 1290, "Whistler, BC", "sunset", "Zoe"),
    CommunityPost(12, R.drawable.community_demo_12, 900, 1000, "Boston Harbor, MA", "sunrise", "Diego"),
)

/**
 * The community feed: two equal columns whose rows are as tall as each photo needs to be,
 * a photo with its place underneath, like a pin board.
 */
@Composable
fun CommunityScreen(modifier: Modifier = Modifier, posts: List<CommunityPost> = SamplePosts) {
    var filter by rememberSaveable { mutableStateOf("all") }
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    val shown = posts.filter { filter == "all" || it.mode == filter }

    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalItemSpacing = 16.dp,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            Column {
                SectionTitle(stringResource(R.string.community_title))
                Spacer(Modifier.height(2.dp))
                Text(
                    stringResource(R.string.community_subtitle),
                    color = TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterPill("all", R.string.community_filter_all, filter) { filter = it }
                    FilterPill("sunrise", R.string.mode_sunrise, filter) { filter = it }
                    FilterPill("sunset", R.string.mode_sunset, filter) { filter = it }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        items(shown, key = { it.id }) { post ->
            PostCard(post, onClick = { opened = post.id })
        }
    }

    posts.firstOrNull { it.id == opened }?.let { post ->
        PhotoViewer(post, onDismiss = { opened = null })
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontFamily = DisplayFamily,
        fontSize = 28.sp,
        fontWeight = FontWeight.W700,
        letterSpacing = (-0.6).sp,
    )
}

@Composable
private fun FilterPill(value: String, label: Int, selected: String, onSelect: (String) -> Unit) {
    Pill(text = stringResource(label), selected = selected == value, onClick = { onSelect(value) })
}

@Composable
private fun PostCard(post: CommunityPost, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        AsyncImage(
            model = post.photo,
            contentDescription = post.place,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(post.aspect)
                .clip(RoundedCornerShape(18.dp)),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.padding(horizontal = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LucideIcon(R.drawable.ic_map_pin, null, tint = Color(0xFFFFD76A), size = 14.dp, modifier = Modifier.padding(top = 2.dp))
            Text(
                post.place,
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.W600,
                lineHeight = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "${stringResource(if (post.mode == "sunrise") R.string.mode_sunrise else R.string.mode_sunset)} · ${post.author}",
            color = TextMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 23.dp, top = 2.dp),
        )
    }
}

@Composable
private fun PhotoViewer(post: CommunityPost, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xE61A2B4F)),
        ) {
            AsyncImage(
                model = post.photo,
                contentDescription = post.place,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .aspectRatio(post.aspect, matchHeightConstraintsFirst = false),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LucideIcon(R.drawable.ic_map_pin, null, tint = Color(0xFFFFD76A), size = 16.dp)
                Column(Modifier.weight(1f)) {
                    Text(post.place, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.W700)
                    Text(
                        "${stringResource(if (post.mode == "sunrise") R.string.mode_sunrise else R.string.mode_sunset)} · ${post.author}",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}
