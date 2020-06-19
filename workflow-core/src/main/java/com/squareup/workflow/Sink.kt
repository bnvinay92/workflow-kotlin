/*
 * Copyright 2019 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.workflow

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * An object that receives values (commonly events or [WorkflowAction]).
 * [RenderContext.actionSink] implements this interface.
 */
interface Sink<in T> {
  fun send(value: T)
}

/**
 * Generates a new sink of type [T2].
 *
 * Given a [transform] closure, the following code is functionally equivalent:
 *
 *    sink.send(transform(value))
 *
 *    sink.contraMap(transform).send(value)
 *
 *  **Trivia**: Why is this called `contraMap`?
 *     - `map` turns `Type<T>` into `Type<U>` via `(T)->U`.
 *     - `contraMap` turns `Type<T>` into `Type<U>` via `(U)->T`
 *
 * Another way to think about this is: `map` transforms a type by changing the
 * output types of its API, while `contraMap` transforms a type by changing the
 * *input* types of its API.
 */
fun <T1, T2> Sink<T1>.contraMap(transform: (T2) -> T1): Sink<T2> {
  return object : Sink<T2> {
    override fun send(value: T2) {
      this@contraMap.send(transform(value))
    }
  }
}

/**
 * Collects from a [Flow] by converting each item into a [WorkflowAction] and then sending them
 * to the [actionSink]. This operator propagates backpressure from the workflow runtime, so if there
 * is a lot of contention on the workflow runtime the flow will be suspended while the action is
 * queued.
 *
 * Example:
 * ```
 * context.runningSideEffect("collector") {
 *   myFlow.collectToSink(context.actionSink) { value ->
 *     action { setOutput(value) }
 *   }
 * }
 * ```
 */
@ExperimentalWorkflow
suspend fun <T, StateT, OutputT : Any> Flow<T>.collectToSink(
  actionSink: Sink<WorkflowAction<StateT, OutputT>>,
  handler: (T) -> WorkflowAction<StateT, OutputT>
) {
  collect {
    // Don't process the emission until the last emission has had its action executed by the
    // workflow runtime.
    actionSink.sendAndAwaitApplication(handler(it))
  }
}

/**
 * Sends [action] to this [Sink] and suspends until after [action]'s [WorkflowAction.apply] method
 * has been invoked. Since a [Sink] may be backed by an unbounded buffer, this method can be used
 * to apply backpressure to the caller when there are a lot events being sent to the workflow
 * runtime.
 *
 * If this coroutine is cancelled before the action gets applied, the action will not be applied.
 */
@ExperimentalWorkflow
suspend fun <StateT, OutputT : Any> Sink<WorkflowAction<StateT, OutputT>>.sendAndAwaitApplication(
  action: WorkflowAction<StateT, OutputT>
) {
  suspendCancellableCoroutine<Unit> { continuation ->
    val resumingAction = action<StateT, OutputT>({ "sendAndAwaitExecution($action)" }) {
      // Don't execute anything if the caller was cancelled while we were in the queue.
      if (!continuation.isActive) return@action

      with(action) {
        // Forward our Updater to the real action.
        apply()
      }
      continuation.resume(Unit)
    }
    send(resumingAction)
  }
}
