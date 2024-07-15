/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.compose.modifiers.background
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.panels.shared.model.SizedTile
import com.android.systemui.qs.panels.ui.viewmodel.EditTileViewModel
import com.android.systemui.qs.panels.ui.viewmodel.PartitionedGridViewModel
import com.android.systemui.qs.panels.ui.viewmodel.TileViewModel
import com.android.systemui.qs.pipeline.domain.interactor.CurrentTilesInteractor
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.res.R
import javax.inject.Inject

@SysUISingleton
class PartitionedGridLayout @Inject constructor(private val viewModel: PartitionedGridViewModel) :
    PaginatableGridLayout {
    @Composable
    override fun TileGrid(
        tiles: List<TileViewModel>,
        modifier: Modifier,
        editModeStart: () -> Unit,
    ) {
        DisposableEffect(tiles) {
            val token = Any()
            tiles.forEach { it.startListening(token) }
            onDispose { tiles.forEach { it.stopListening(token) } }
        }
        val columns by viewModel.columns.collectAsStateWithLifecycle()
        val showLabels by viewModel.showLabels.collectAsStateWithLifecycle()
        val largeTileHeight = tileHeight()
        val iconTileHeight = tileHeight(showLabels)
        val (smallTiles, largeTiles) = tiles.partition { viewModel.isIconTile(it.spec) }

        TileLazyGrid(modifier = modifier, columns = GridCells.Fixed(columns)) {
            // Large tiles
            items(largeTiles.size, span = { GridItemSpan(2) }) { index ->
                Tile(
                    tile = largeTiles[index],
                    iconOnly = false,
                    modifier = Modifier.height(largeTileHeight)
                )
            }
            fillUpRow(nTiles = largeTiles.size, columns = columns / 2)

            // Small tiles
            items(smallTiles.size) { index ->
                Tile(
                    tile = smallTiles[index],
                    iconOnly = true,
                    showLabels = showLabels,
                    modifier = Modifier.height(iconTileHeight)
                )
            }
        }
    }

    @Composable
    override fun EditTileGrid(
        tiles: List<EditTileViewModel>,
        modifier: Modifier,
        onAddTile: (TileSpec, Int) -> Unit,
        onRemoveTile: (TileSpec) -> Unit,
    ) {
        val columns by viewModel.columns.collectAsStateWithLifecycle()
        val showLabels by viewModel.showLabels.collectAsStateWithLifecycle()

        val listState = rememberEditListState(tiles)
        val dragAndDropState = rememberDragAndDropState(listState)

        val (currentTiles, otherTiles) = listState.tiles.partition { it.isCurrent }
        val addTileToEnd: (TileSpec) -> Unit by rememberUpdatedState {
            onAddTile(it, CurrentTilesInteractor.POSITION_AT_END)
        }
        val onDoubleTap: (TileSpec) -> Unit by rememberUpdatedState { tileSpec ->
            viewModel.resize(tileSpec, !viewModel.isIconTile(tileSpec))
        }
        val largeTileHeight = tileHeight()
        val iconTileHeight = tileHeight(showLabels)
        val tilePadding = dimensionResource(R.dimen.qs_tile_margin_vertical)

        Column(
            verticalArrangement = Arrangement.spacedBy(tilePadding),
            modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier =
                    Modifier.background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            alpha = { 1f },
                            shape = RoundedCornerShape(dimensionResource(R.dimen.qs_corner_radius))
                        )
                        .padding(tilePadding)
            ) {
                Column(Modifier.padding(start = tilePadding)) {
                    Text(
                        text = "Show text labels",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Display names under each tile",
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Switch(checked = showLabels, onCheckedChange = { viewModel.setShowLabels(it) })
            }

            CurrentTiles(
                tiles = currentTiles,
                largeTileHeight = largeTileHeight,
                iconTileHeight = iconTileHeight,
                tilePadding = tilePadding,
                onAdd = onAddTile,
                onRemove = onRemoveTile,
                onDoubleTap = onDoubleTap,
                isIconOnly = viewModel::isIconTile,
                columns = columns,
                showLabels = showLabels,
                dragAndDropState = dragAndDropState,
            )
            AvailableTiles(
                tiles = otherTiles.filter { !dragAndDropState.isMoving(it.tileSpec) },
                largeTileHeight = largeTileHeight,
                iconTileHeight = iconTileHeight,
                tilePadding = tilePadding,
                addTileToEnd = addTileToEnd,
                onRemove = onRemoveTile,
                onDoubleTap = onDoubleTap,
                isIconOnly = viewModel::isIconTile,
                showLabels = showLabels,
                columns = columns,
                dragAndDropState = dragAndDropState,
            )
        }
    }

    override fun splitIntoPages(
        tiles: List<TileViewModel>,
        rows: Int,
        columns: Int,
    ): List<List<TileViewModel>> {
        val (smallTiles, largeTiles) = tiles.partition { viewModel.isIconTile(it.spec) }

        val sizedLargeTiles = largeTiles.map { SizedTile(it, 2) }
        val sizedSmallTiles = smallTiles.map { SizedTile(it, 1) }
        val largeTilesRows = PaginatableGridLayout.splitInRows(sizedLargeTiles, columns)
        val smallTilesRows = PaginatableGridLayout.splitInRows(sizedSmallTiles, columns)
        return (largeTilesRows + smallTilesRows).chunked(rows).map { it.flatten().map { it.tile } }
    }

    @Composable
    private fun CurrentTiles(
        tiles: List<EditTileViewModel>,
        largeTileHeight: Dp,
        iconTileHeight: Dp,
        tilePadding: Dp,
        onAdd: (TileSpec, Int) -> Unit,
        onRemove: (TileSpec) -> Unit,
        onDoubleTap: (TileSpec) -> Unit,
        isIconOnly: (TileSpec) -> Boolean,
        showLabels: Boolean,
        columns: Int,
        dragAndDropState: DragAndDropState,
    ) {
        val (smallTiles, largeTiles) = tiles.partition { isIconOnly(it.tileSpec) }

        val largeGridHeight = gridHeight(largeTiles.size, largeTileHeight, columns / 2, tilePadding)
        val smallGridHeight = gridHeight(smallTiles.size, iconTileHeight, columns, tilePadding)

        CurrentTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier =
                    Modifier.height(largeGridHeight)
                        .dragAndDropTileList(dragAndDropState, { !isIconOnly(it) }, onAdd)
            ) {
                editTiles(
                    tiles = largeTiles,
                    clickAction = ClickAction.REMOVE,
                    onClick = onRemove,
                    onDoubleTap = onDoubleTap,
                    isIconOnly = { false },
                    dragAndDropState = dragAndDropState,
                    acceptDrops = { !isIconOnly(it) },
                    onDrop = onAdd,
                    indicatePosition = true,
                )
            }
        }

        CurrentTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier =
                    Modifier.height(smallGridHeight)
                        .dragAndDropTileList(dragAndDropState, { isIconOnly(it) }, onAdd)
            ) {
                editTiles(
                    tiles = smallTiles,
                    clickAction = ClickAction.REMOVE,
                    onClick = onRemove,
                    onDoubleTap = onDoubleTap,
                    isIconOnly = { true },
                    showLabels = showLabels,
                    dragAndDropState = dragAndDropState,
                    acceptDrops = { isIconOnly(it) },
                    onDrop = onAdd,
                    indicatePosition = true,
                )
            }
        }
    }

    @Composable
    private fun AvailableTiles(
        tiles: List<EditTileViewModel>,
        largeTileHeight: Dp,
        iconTileHeight: Dp,
        tilePadding: Dp,
        addTileToEnd: (TileSpec) -> Unit,
        onRemove: (TileSpec) -> Unit,
        onDoubleTap: (TileSpec) -> Unit,
        isIconOnly: (TileSpec) -> Boolean,
        showLabels: Boolean,
        columns: Int,
        dragAndDropState: DragAndDropState,
    ) {
        val (tilesStock, tilesCustom) = tiles.partition { it.appName == null }
        val (smallTiles, largeTiles) = tilesStock.partition { isIconOnly(it.tileSpec) }

        val largeGridHeight = gridHeight(largeTiles.size, largeTileHeight, columns / 2, tilePadding)
        val smallGridHeight = gridHeight(smallTiles.size, iconTileHeight, columns, tilePadding)
        val largeGridHeightCustom =
            gridHeight(tilesCustom.size, iconTileHeight, columns, tilePadding)

        // Add up the height of all three grids and add padding in between
        val gridHeight =
            largeGridHeight + smallGridHeight + largeGridHeightCustom + (tilePadding * 2)

        val onDrop: (TileSpec, Int) -> Unit by rememberUpdatedState { tileSpec, _ ->
            onRemove(tileSpec)
        }

        AvailableTilesContainer {
            TileLazyGrid(
                columns = GridCells.Fixed(columns),
                modifier =
                    Modifier.height(gridHeight)
                        .dragAndDropTileList(dragAndDropState, { true }, onDrop)
            ) {
                // Large tiles
                editTiles(
                    largeTiles,
                    ClickAction.ADD,
                    addTileToEnd,
                    isIconOnly,
                    dragAndDropState,
                    onDoubleTap = onDoubleTap,
                    acceptDrops = { true },
                    onDrop = onDrop,
                )
                fillUpRow(nTiles = largeTiles.size, columns = columns / 2)

                // Small tiles
                editTiles(
                    smallTiles,
                    ClickAction.ADD,
                    addTileToEnd,
                    isIconOnly,
                    dragAndDropState,
                    onDoubleTap = onDoubleTap,
                    showLabels = showLabels,
                    acceptDrops = { true },
                    onDrop = onDrop,
                )
                fillUpRow(nTiles = smallTiles.size, columns = columns)

                // Custom tiles, all icons
                editTiles(
                    tilesCustom,
                    ClickAction.ADD,
                    addTileToEnd,
                    isIconOnly,
                    dragAndDropState,
                    onDoubleTap = onDoubleTap,
                    showLabels = showLabels,
                    acceptDrops = { true },
                    onDrop = onDrop,
                )
            }
        }
    }

    @Composable
    private fun CurrentTilesContainer(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth()
                .dashedBorder(
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = .5f),
                    shape = Dimensions.ContainerShape,
                )
                .padding(dimensionResource(R.dimen.qs_tile_margin_vertical))
        ) {
            content()
        }
    }

    @Composable
    private fun AvailableTilesContainer(content: @Composable () -> Unit) {
        Box(
            Modifier.fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.background,
                    alpha = { 1f },
                    shape = Dimensions.ContainerShape,
                )
                .padding(dimensionResource(R.dimen.qs_tile_margin_vertical))
        ) {
            content()
        }
    }

    /** Fill up the rest of the row if it's not complete. */
    private fun LazyGridScope.fillUpRow(nTiles: Int, columns: Int) {
        if (nTiles % columns != 0) {
            item(span = { GridItemSpan(maxCurrentLineSpan) }) { Spacer(Modifier) }
        }
    }

    private fun Modifier.dashedBorder(
        color: Color,
        shape: Shape,
    ): Modifier {
        return this.drawWithContent {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path()
            path.addOutline(outline)
            val stroke =
                Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f))
                )
            this.drawContent()
            drawPath(path = path, style = stroke, color = color)
        }
    }

    private object Dimensions {
        // Corner radius is half the height of a tile + padding
        val ContainerShape = RoundedCornerShape(48.dp)
    }
}
