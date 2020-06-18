package com.squareup.workflow.internal

/**
 * TODO write documentation
 */
internal object NoOutput {
  @Suppress("UNCHECKED_CAST")
  inline fun <T, R> T.letUnlessNoOutput(block: (T) -> R): R =
    if (this === NoOutput) NoOutput as R else block(this)
}

@Suppress("UNCHECKED_CAST", "NOTHING_TO_INLINE")
internal inline fun <T> noOutput(): T = NoOutput as T
