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

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.runtime.MutableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

enum class ScrollStrategy {
	EnterAlways {
		override fun create(
			offsetY: MutableState<Int>,
			toolbarState: CollapsingToolbarState,
			flingBehavior: FlingBehavior,
			snapStrategy: SnapStrategy?,
		): NestedScrollConnection =
			EnterAlwaysNestedScrollConnection(offsetY, toolbarState, flingBehavior, snapStrategy)
	},
	EnterAlwaysCollapsed {
		override fun create(
			offsetY: MutableState<Int>,
			toolbarState: CollapsingToolbarState,
			flingBehavior: FlingBehavior,
			snapStrategy: SnapStrategy?,
		): NestedScrollConnection =
			EnterAlwaysCollapsedNestedScrollConnection(offsetY, toolbarState, flingBehavior, snapStrategy)
	},
	ExitUntilCollapsed {
		override fun create(
			offsetY: MutableState<Int>,
			toolbarState: CollapsingToolbarState,
			flingBehavior: FlingBehavior,
			snapStrategy: SnapStrategy?,
		): NestedScrollConnection =
			ExitUntilCollapsedNestedScrollConnection(toolbarState, flingBehavior, snapStrategy)
	};

	internal abstract fun create(
		offsetY: MutableState<Int>,
		toolbarState: CollapsingToolbarState,
		flingBehavior: FlingBehavior,
		snapStrategy: SnapStrategy?,
	): NestedScrollConnection
}

private class ScrollDelegate(
	private val offsetY: MutableState<Int>
) {
	private var scrollToBeConsumed: Float = 0f

	fun doScroll(delta: Float) {
		val scroll = scrollToBeConsumed + delta
		val scrollInt = scroll.toInt()

		scrollToBeConsumed = scroll - scrollInt

		offsetY.value += scrollInt
	}
}

internal class EnterAlwaysNestedScrollConnection(
	private val offsetY: MutableState<Int>,
	private val toolbarState: CollapsingToolbarState,
	private val flingBehavior: FlingBehavior,
	private val snapStrategy: SnapStrategy?
): NestedScrollConnection {
	private val scrollDelegate = ScrollDelegate(offsetY)
	private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

	override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
		val dy = available.y
		tracker.delta(dy)

		val toolbar = toolbarState.height.toFloat()
		val offset = offsetY.value.toFloat()

		// -toolbarHeight <= offsetY + dy <= 0
		val consume = if(dy < 0) {
			val toolbarConsumption = toolbarState.dispatchRawDelta(dy)
			val remaining = dy - toolbarConsumption
			val offsetConsumption = remaining.coerceAtLeast(-toolbar - offset)
			scrollDelegate.doScroll(offsetConsumption)

			toolbarConsumption + offsetConsumption
		}else{
			val offsetConsumption = dy.coerceAtMost(-offset)
			scrollDelegate.doScroll(offsetConsumption)

			val toolbarConsumption = toolbarState.dispatchRawDelta(dy - offsetConsumption)

			offsetConsumption + toolbarConsumption
		}

		return Offset(0f, consume)
	}

	override suspend fun onPreFling(available: Velocity): Velocity {
		val velocity = tracker.reset()

		val left = if(velocity > 0) {
			toolbarState.fling(flingBehavior, velocity)
		}else{
			// If velocity < 0, the main content should have a remaining scroll space
			// so the scroll resumes to the onPreScroll(..., Fling) phase. Hence we do
			// not need to process it at onPostFling() manually.
			velocity
		}

		return available.copy(y = available.y - left)
	}

	override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
		// TODO: Cancel expand/collapse animation inside onPreScroll
		snapStrategy?.let {
			val isToolbarChangingOffset = offsetY.value != 0
			if (isToolbarChangingOffset) {
				// When the toolbar is hiding, it does it through changing the offset and does not
				// change its height, so we must process not the snap of the toolbar, but the
				// snap of its offset.
				toolbarState.processOffsetSnap(it, offsetY)
			} else {
				toolbarState.processSnap(it)
			}
		}

		return super.onPostFling(consumed, available)
	}
}

internal class EnterAlwaysCollapsedNestedScrollConnection(
	private val offsetY: MutableState<Int>,
	private val toolbarState: CollapsingToolbarState,
	private val flingBehavior: FlingBehavior,
	private val snapStrategy: SnapStrategy?,
): NestedScrollConnection {
	private val scrollDelegate = ScrollDelegate(offsetY)
	private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

	override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
		val dy = available.y
		tracker.delta(dy)

		val consumed = if(dy > 0) { // expanding: offset -> body -> toolbar
			val offsetConsumption = dy.coerceAtMost(-offsetY.value.toFloat())
			scrollDelegate.doScroll(offsetConsumption)

			offsetConsumption
		}else{ // collapsing: toolbar -> offset -> body
			val toolbarConsumption = toolbarState.dispatchRawDelta(dy)
			val offsetConsumption = (dy - toolbarConsumption).coerceAtLeast(-toolbarState.height.toFloat() - offsetY.value)

			scrollDelegate.doScroll(offsetConsumption)

			toolbarConsumption + offsetConsumption
		}

		return Offset(0f, consumed)
	}

	override fun onPostScroll(
		consumed: Offset,
		available: Offset,
		source: NestedScrollSource
	): Offset {
		val dy = available.y

		return if(dy > 0) {
			Offset(0f, toolbarState.dispatchRawDelta(dy))
		}else{
			Offset(0f, 0f)
		}
	}

	override suspend fun onPreFling(available: Velocity): Velocity =
		available.copy(y = tracker.deriveDelta(available.y))

	override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
		val dy = available.y

		val left = if(dy > 0) {
			// onPostFling() has positive available scroll value only called if the main scroll
			// has leftover scroll, i.e. the scroll of the main content has done. So we just process
			// fling if the available value is positive.
			toolbarState.fling(flingBehavior, dy)
		}else{
			dy
		}

		// TODO: Cancel expand/collapse animation inside onPreScroll
		snapStrategy?.let {
			val isToolbarChangingOffset = offsetY.value != 0//toolbarState.progress == 0f
			if (isToolbarChangingOffset) {
				// When the toolbar is hiding, it does it through changing the offset and does not
				// change its height, so we must process not the snap of the toolbar, but the
				// snap of its offset.
				toolbarState.processOffsetSnap(it, offsetY)
			} else {
				toolbarState.processSnap(it)
			}
		}

		return available.copy(y = available.y - left)
	}
}

internal class ExitUntilCollapsedNestedScrollConnection(
	private val toolbarState: CollapsingToolbarState,
	private val flingBehavior: FlingBehavior,
	private val snapStrategy: SnapStrategy?
): NestedScrollConnection {
	private val tracker = RelativeVelocityTracker(CurrentTimeProviderImpl())

	override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
		val dy = available.y
		tracker.delta(dy)

		val consume = if(dy < 0) { // collapsing: toolbar -> body
			toolbarState.dispatchRawDelta(dy)
		}else{
			0f
		}

		return Offset(0f, consume)
	}

	override fun onPostScroll(
		consumed: Offset,
		available: Offset,
		source: NestedScrollSource
	): Offset {
		val dy = available.y

		val consume = if(dy > 0) { // expanding: body -> toolbar
			toolbarState.dispatchRawDelta(dy)
		}else{
			0f
		}

		return Offset(0f, consume)
	}

	override suspend fun onPreFling(available: Velocity): Velocity {
		val velocity = tracker.reset()

		val left = if(velocity < 0) {
			toolbarState.fling(flingBehavior, velocity)
		}else{
			velocity
		}

		return available.copy(y = available.y - left)
	}

	override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
		val velocity = available.y

		val left = if(velocity > 0) {
			toolbarState.fling(flingBehavior, velocity)
		}else{
			velocity
		}

		// TODO: Cancel expand/collapse animation inside onPreScroll
		snapStrategy?.let { toolbarState.processSnap(it) }

		return available.copy(y = available.y - left)
	}
}
