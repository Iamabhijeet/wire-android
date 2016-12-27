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
package com.waz.zclient.messages.parts.assets

import android.graphics._
import android.graphics.drawable.Drawable
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.ProgressDotsDrawable
import com.waz.zclient.{R, WireContext}

class AssetBackground(showDots: Signal[Boolean])(implicit context: WireContext, eventContext: EventContext) extends Drawable with Drawable.Callback {
  private val cornerRadius = ViewUtils.toPx(context, 4).toFloat

  private val backgroundPaint = new Paint
  backgroundPaint.setColor(getColor(R.color.light_graphite_8))

  private val dots = new ProgressDotsDrawable
  dots.setCallback(this)

  private var show = false

  showDots.on(Threading.Ui) { s =>
    show = s
    invalidateSelf()
  }

  override def draw(canvas: Canvas): Unit = {
    canvas.drawRoundRect(new RectF(getBounds), cornerRadius, cornerRadius, backgroundPaint)
    if (show) dots.draw(canvas)
  }

  override def setColorFilter(colorFilter: ColorFilter): Unit = {
    backgroundPaint.setColorFilter(colorFilter)
    dots.setColorFilter(colorFilter)
  }

  override def setAlpha(alpha: Int): Unit = {
    backgroundPaint.setAlpha(alpha)
    dots.setAlpha(alpha)
  }

  override def getOpacity: Int = PixelFormat.TRANSLUCENT

  override def scheduleDrawable(who: Drawable, what: Runnable, when: Long): Unit = scheduleSelf(what, when)

  override def invalidateDrawable(who: Drawable): Unit = invalidateSelf()

  override def unscheduleDrawable(who: Drawable, what: Runnable): Unit = unscheduleSelf(what)
}
