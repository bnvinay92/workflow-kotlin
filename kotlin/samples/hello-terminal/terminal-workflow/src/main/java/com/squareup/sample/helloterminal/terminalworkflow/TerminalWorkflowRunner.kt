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
package com.squareup.sample.helloterminal.terminalworkflow

import com.googlecode.lanterna.TerminalPosition.TOP_LEFT_CORNER
import com.googlecode.lanterna.screen.Screen.RefreshType.COMPLETE
import com.googlecode.lanterna.screen.TerminalScreen
import com.googlecode.lanterna.terminal.DefaultTerminalFactory
import com.squareup.workflow.Worker
import com.squareup.workflow.Workflow
import com.squareup.workflow.WorkflowHost
import com.squareup.workflow.WorkflowHost.Update
import com.squareup.workflow.asWorker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.selectUnbiased
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Hosts [Workflow]s that:
 *  - gets information about the terminal configuration as input
 *  - renders the text to display on the terminal
 *  - finishes by emitting an exit code that should be passed to [kotlin.system.exitProcess].
 *
 * @param hostFactory Used to create the actual [WorkflowHost] that hosts workflows. Any dispatcher
 * configured on the host will be ignored, to ensure that key events stay in sync with renderings.
 * @param ioDispatcher Defaults to [Dispatchers.IO] and is used to listen for key events using
 * blocking APIs.
 */
class TerminalWorkflowRunner(
  private val hostFactory: WorkflowHost.Factory = WorkflowHost.Factory(EmptyCoroutineContext),
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

  private val screen = DefaultTerminalFactory().createScreen()

  /**
   * Runs [workflow] until it emits an [ExitCode] and then returns it.
   */
  @UseExperimental(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
  // Some methods on screen are synchronized, which Kotlin detects as blocking and warns us about
  // when invoking from coroutines. This entire function is blocking however, so we don't care.
  @Suppress("BlockingMethodInNonBlockingContext")
  fun run(workflow: TerminalWorkflow): ExitCode = runBlocking {
    val configs = Channel<TerminalInput>(CONFLATED)
    val host = hostFactory.run(workflow, configs, context = coroutineContext)
    val keyStrokesChannel = screen.listenForKeyStrokesOn(this + ioDispatcher)
    val keyStrokesWorker = keyStrokesChannel.asWorker()
    val resizes = screen.terminal.listenForResizesOn(this)

    // Hide the cursor.
    screen.cursorPosition = null

    try {
      screen.startScreen()
      try {
        runTerminalWorkflow(screen, configs, host, keyStrokesWorker, resizes)
      } finally {
        screen.stopScreen()
      }
    } finally {
      // Cancel all the coroutines we started so the coroutineScope block will actually exit if no
      // exception was thrown.
      host.updates.cancel()
      keyStrokesChannel.cancel()
      resizes.cancel()
    }
  }
}

@Suppress("BlockingMethodInNonBlockingContext")
private suspend fun runTerminalWorkflow(
  screen: TerminalScreen,
  inputs: SendChannel<TerminalInput>,
  host: WorkflowHost<ExitCode, TerminalRendering>,
  keyStrokes: Worker<KeyStroke>,
  resizes: ReceiveChannel<TerminalSize>
): ExitCode {
  var input = TerminalInput(screen.terminalSize.toSize(), keyStrokes)

  // Need to send an initial input for the workflow to start running.
  inputs.offer(input)

  while (true) {
    val update = selectUnbiased<Update<ExitCode, TerminalRendering>> {
      resizes.onReceive {
        screen.doResizeIfNecessary()
            ?.let {
              // If the terminal was resized since the last iteration, we need to notify the
              // workflow.
              input = input.copy(size = it.toSize())
            }

        // Publish config changes to the workflow.
        inputs.send(input)

        // Sending that new input invalidated the lastRendering, so we don't want to
        // re-iterate until we have a new rendering with a fresh event handler. It also
        // triggered a render pass, so we can just retrieve that immediately.
        return@onReceive host.updates.receive()
      }

      host.updates.onReceive { it }
    }

    // Stop the runner and return the exit code as soon as the workflow emits one.
    update.output?.let { exitCode ->
      return exitCode
    }

    screen.clear()
    screen.newTextGraphics()
        .apply {
          foregroundColor = update.rendering.textColor.toTextColor()
          backgroundColor = update.rendering.backgroundColor.toTextColor()
          update.rendering.text.lineSequence()
              .forEachIndexed { index, line ->
                putString(TOP_LEFT_CORNER.withRelativeRow(index), line)
              }
        }

    screen.refresh(COMPLETE)
  }
}
