/*
 * Copyright (c) 2021 onebone <me@onebone.me>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

package me.onebone.toolbar

import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import kotlin.math.max

@Stable
class CollapsingToolbarScaffoldState(
	val toolbarState: CollapsingToolbarState,
	initialOffsetY: Int = 0
) {
	val offsetY: Int
		get() = offsetYState.value

	internal val offsetYState = mutableStateOf(initialOffsetY)
}

private class CollapsingToolbarScaffoldStateSaver(
	private val toolbarState: CollapsingToolbarState
): Saver<CollapsingToolbarScaffoldState, List<Any>> {
	override fun restore(value: List<Any>): CollapsingToolbarScaffoldState =
		CollapsingToolbarScaffoldState(
			toolbarState.apply {
				height = value[0] as Int
			},
			value[1] as Int
		)

	override fun SaverScope.save(value: CollapsingToolbarScaffoldState): List<Any> =
		listOf(
			value.toolbarState.height,
			value.offsetY
	)
}

@Composable
fun rememberCollapsingToolbarScaffoldState(
	toolbarState: CollapsingToolbarState = rememberCollapsingToolbarState()
): CollapsingToolbarScaffoldState {
	return rememberSaveable(toolbarState, saver = CollapsingToolbarScaffoldStateSaver(toolbarState)) {
		CollapsingToolbarScaffoldState(toolbarState)
	}
}

interface CollapsingToolbarScaffoldScope {
	@ExperimentalToolbarApi
	fun Modifier.align(alignment: Alignment): Modifier
}

@Composable
fun CollapsingToolbarScaffold(
	modifier: Modifier,
	state: CollapsingToolbarScaffoldState,
	scrollStrategy: ScrollStrategy,
	enabled: Boolean = true,
	toolbarModifier: Modifier = Modifier,
	toolbarClipToBounds: Boolean = true,
	toolbar: @Composable CollapsingToolbarScope.() -> Unit,
	body: @Composable CollapsingToolbarScaffoldScope.() -> Unit
) {
	val flingBehavior = ScrollableDefaults.flingBehavior()
	val layoutDirection = LocalLayoutDirection.current

	val nestedScrollConnection = remember(scrollStrategy, state) {
		scrollStrategy.create(state.offsetYState, state.toolbarState, flingBehavior)
	}

	val toolbarState = state.toolbarState

	Layout(
		content = {
			CollapsingToolbar(
				modifier = toolbarModifier,
				clipToBounds = toolbarClipToBounds,
				collapsingToolbarState = toolbarState,
			) {
				toolbar()
			}

			CollapsingToolbarScaffoldScopeInstance.body()
		},
		modifier = modifier
			.then(
				if (enabled) {
					Modifier.nestedScroll(nestedScrollConnection)
				} else {
					Modifier
				}
			)
	) { measurables, constraints ->
		check(measurables.size >= 2) {
			"the number of children should be at least 2: toolbar, (at least one) body"
		}

		val toolbarConstraints = constraints.copy(
			minWidth = 0,
			minHeight = 0
		)
		val bodyConstraints = constraints.copy(
			minWidth = 0,
			minHeight = 0,
			maxHeight = when (scrollStrategy) {
				ScrollStrategy.ExitUntilCollapsed ->
					(constraints.maxHeight - toolbarState.minHeight).coerceAtLeast(0)

				ScrollStrategy.EnterAlways, ScrollStrategy.EnterAlwaysCollapsed ->
					constraints.maxHeight
			}
		)

		val toolbarPlaceable = measurables[0].measure(toolbarConstraints)

		val bodyMeasurables = measurables.subList(1, measurables.size)
		val childrenAlignments = bodyMeasurables.map {
			(it.parentData as? ScaffoldParentData)?.alignment
		}
		val bodyPlaceables = bodyMeasurables.map {
			it.measure(bodyConstraints)
		}

		val toolbarHeight = toolbarPlaceable.height

		val width = max(
			toolbarPlaceable.width,
			bodyPlaceables.maxOfOrNull { it.width } ?: 0
		).coerceIn(constraints.minWidth, constraints.maxWidth)
		val height = max(
			toolbarHeight,
			bodyPlaceables.maxOfOrNull { it.height } ?: 0
		).coerceIn(constraints.minHeight, constraints.maxHeight)

		layout(width, height) {
			bodyPlaceables.forEachIndexed { index, placeable ->
				val alignment = childrenAlignments[index]

				if (alignment == null) {
					placeable.placeRelative(0, toolbarHeight + state.offsetY)
				} else {
					val offset = alignment.align(
						size = IntSize(placeable.width, placeable.height),
						space = IntSize(width, height),
						layoutDirection = layoutDirection
					)
					placeable.place(offset)
				}
			}
			toolbarPlaceable.placeRelative(0, state.offsetY)
		}
	}
}

internal object CollapsingToolbarScaffoldScopeInstance: CollapsingToolbarScaffoldScope {
	@ExperimentalToolbarApi
	override fun Modifier.align(alignment: Alignment): Modifier =
		this.then(ScaffoldChildAlignmentModifier(alignment))
}

private class ScaffoldChildAlignmentModifier(
	private val alignment: Alignment
) : ParentDataModifier {
	override fun Density.modifyParentData(parentData: Any?): Any {
		return (parentData as? ScaffoldParentData) ?: ScaffoldParentData(alignment)
	}
}

private data class ScaffoldParentData(
	var alignment: Alignment? = null
)
