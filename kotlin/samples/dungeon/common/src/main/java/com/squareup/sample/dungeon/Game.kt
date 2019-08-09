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
package com.squareup.sample.dungeon

import com.squareup.sample.dungeon.board.Board
import com.squareup.sample.dungeon.board.Board.Location
import com.squareup.sample.dungeon.board.BoardCell

private val PLACEHOLDER_CELL = BoardCell("?")

data class Game(
  val board: Board,
  val playerLocation: Location,
  val aiActors: List<Location>
) {

  val isPlayerEaten: Boolean get() = aiActors.any { it == playerLocation }

  override fun toString(): String = board.withOverlay(
      (aiActors + playerLocation)
          .map { it to PLACEHOLDER_CELL }
          .toMap()).toString()
}
