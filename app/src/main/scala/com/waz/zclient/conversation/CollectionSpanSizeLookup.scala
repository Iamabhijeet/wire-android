/**
  * Wire
  * Copyright (C) 2016 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.conversation

import android.support.v7.widget.GridLayoutManager
import com.waz.ZLog._

class CollectionSpanSizeLookup(val spanCount: Int, val adapter: CollectionAdapter) extends GridLayoutManager.SpanSizeLookup {

  private implicit val tag: LogTag = logTagFor[CollectionSpanSizeLookup]

  override def getSpanSize(position: Int): Int = {
    if (adapter.isFullSpan(position)) {
      spanCount
    } else if (isLastBeforeHeader(position)) {
      val itemsInCategory = getNumberOfItemsBeforePositionInCategory(position)
      val columnIndex = itemsInCategory % spanCount
      1 + (spanCount - (columnIndex + 1))
    } else {
      1
    }
  }

  def isLastBeforeHeader(position: Int): Boolean = {
    val headerId = adapter.getHeaderId(position)
    val nextPosition = position + 1
    val res = nextPosition >= 0 && nextPosition < adapter.getItemCount && headerId != adapter.getHeaderId(nextPosition)
    res
  }

  def getNumberOfItemsBeforePositionInCategory(position: Int): Int = {
    val categoryId = adapter.getHeaderId(position)

    for(i <- 1 to position) {
      if(adapter.getHeaderId(position - i) != categoryId) {
        return i - 1
      }
    }
    position
  }

}
