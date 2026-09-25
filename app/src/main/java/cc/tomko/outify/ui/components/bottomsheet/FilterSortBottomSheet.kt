package cc.tomko.outify.ui.components.bottomsheet

import cc.tomko.outify.R

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cc.tomko.outify.ui.viewmodel.library.ExplicitFilter
import cc.tomko.outify.ui.viewmodel.library.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSortBottomSheet(
    onDismissRequest: () -> Unit,
    explicitFilter: ExplicitFilter,
    onExplicitFilterChange: (ExplicitFilter) -> Unit,
    artistNameFilter: String,
    onArtistNameFilterChange: (String) -> Unit,
    trackNameFilter: String,
    onTrackNameFilterChange: (String) -> Unit,
    sortBy: SortBy,
    onSortByChange: (SortBy) -> Unit,
    sortAscending: Boolean,
    onSortAscendingChange: (Boolean) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.FilterAlt,
                    contentDescription = stringResource(R.string.common_filter_and_sort),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = stringResource(R.string.filter_sort),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            // Filters Section
            Text(
                text = stringResource(R.string.filter_filters),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Explicit Filter
            Column(
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Text(
                    text = stringResource(R.string.filter_explicit),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                val explicitOptions = listOf(
                    ExplicitFilter.BOTH to stringResource(R.string.filter_show_all),
                    ExplicitFilter.EXPLICIT_ONLY to stringResource(R.string.filter_explicit_only),
                    ExplicitFilter.NON_EXPLICIT_ONLY to stringResource(R.string.filter_non_explicit_only)
                )
                explicitOptions.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = explicitFilter == option,
                            onClick = { onExplicitFilterChange(option) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // Artist Name Filter
            TextField(
                value = artistNameFilter,
                onValueChange = onArtistNameFilterChange,
                label = { Text(stringResource(R.string.filter_artist_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                singleLine = true
            )

            // Track Name Filter
            TextField(
                value = trackNameFilter,
                onValueChange = onTrackNameFilterChange,
                label = { Text(stringResource(R.string.filter_track_name)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                singleLine = true
            )

            // Sort Section
            Text(
                text = stringResource(R.string.filter_sort_by),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Sort Options
            val sortOptions = listOf(
                SortBy.POSITION to stringResource(R.string.sort_added_default),
                SortBy.ARTIST_NAME to stringResource(R.string.sort_artist_name),
                SortBy.TRACK_NAME to stringResource(R.string.sort_track_name),
                SortBy.DURATION to stringResource(R.string.sort_duration)
            )

            Column(
                modifier = Modifier.padding(bottom = 20.dp)
            ) {
                sortOptions.forEach { (option, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sortBy == option,
                            onClick = { onSortByChange(option) }
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }

            // Sort Direction
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (sortAscending) stringResource(R.string.sort_ascending) else stringResource(R.string.sort_descending),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = sortAscending,
                    onCheckedChange = onSortAscendingChange
                )
            }

            // Bottom spacing
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}
