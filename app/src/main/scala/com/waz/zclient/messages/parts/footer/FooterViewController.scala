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
package com.waz.zclient.messages.parts.footer

import android.content.Context
import com.waz.api.Message.Status
import com.waz.model.ConversationData.ConversationType
import com.waz.model.MessageData
import com.waz.service.ZMessaging
import com.waz.service.messages.MessageAndLikes
import com.waz.threading.CancellableFuture
import com.waz.utils._
import com.waz.utils.events.{ClockSignal, EventContext, Signal}
import com.waz.zclient.controllers.global.{AccentColorController, SelectionController}
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.SyncEngineSignals
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.ZTimeFormatter
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.{DateTimeUtils, Instant}

import scala.concurrent.duration._

class FooterViewController(implicit inj: Injector, context: Context, ec: EventContext) extends Injectable {
  import FooterViewController._
  import com.waz.threading.Threading.Implicits.Ui

  val zms = inject[Signal[ZMessaging]]
  val accents = inject[AccentColorController]
  val selection = inject[SelectionController].messages
  val signals = inject[SyncEngineSignals]
  val reactions = zms.map(_.reactions)

  val opts = Signal[MsgBindOptions]()
  val messageAndLikes = Signal[MessageAndLikes]()
  val isSelfMessage = opts.map(_.isSelf)
  val message = messageAndLikes.map(_.message)
  val isLiked = messageAndLikes.map(_.likes.nonEmpty)
  val likedBySelf = messageAndLikes.map(_.likedBySelf)
  val expiring = message.map { msg => msg.isEphemeral && !msg.expired && msg.expiryTime.isDefined }

  val focusedTime = selection.focused.zip(message.map(_.id)).map {
    case (Some((selectedId, time)), thisId) if selectedId == thisId => time
    case _ => Instant.EPOCH
  }

  val focused = focusedTime flatMap { time =>
    val delay = Instant.now.until(time.plus(FocusTimeout)).asScala
    if (delay.isNegative) Signal const false
    else Signal.future(CancellableFuture.delayed(delay)(false)).orElse(Signal const true) // signal `true` switching to `false` after delay
  }

  val showTimestamp: Signal[Boolean] = for {
    liked     <- isLiked
    selfMsg   <- isSelfMessage
    expiring  <- expiring
    focused   <- focused
  } yield
    focused || expiring || (selfMsg && !liked)

  val ephemeralTimeout: Signal[Option[FiniteDuration]] = message.map(_.expiryTime) flatMap {
    case None => Signal const None
    case Some(expiry) if expiry <= Instant.now => Signal const None
    case Some(expiry) =>
      ClockSignal(1.second) map { now =>
        Some(now.until(expiry).asScala).filterNot(_.isNegative)
      }
  }

  val conv = signals.conv(message)

  val timestampText = for {
    selfUserId  <- signals.selfUserId
    convType    <- conv.map(_.convType)
    msg         <- message
    timeout     <- ephemeralTimeout
  } yield {
    val timestamp = ZTimeFormatter.getSingleMessageTime(context, DateTimeUtils.toDate(msg.time))
    if (selfUserId == msg.userId) {
      timeout.fold(statusString(timestamp, msg, convType))(ephemeralTimeoutString(timestamp, _))
    } else
      timestamp
  }

  val linkColor = expiring flatMap {
    case true => accents.accentColor.map(_.getColor())
    case false => Signal const getColor(R.color.accent_red);
  }

  val linkCallback = new Runnable() {
    def run() = {
      //TODO retry
    }
  }

  def onLikeClicked() = for {
    reacts <- reactions.head
    mAndL <- messageAndLikes.head
  } {
    val msg = mAndL.message
    selection.setFocused(msg.id)
    if (mAndL.likedBySelf) reacts.unlike(msg.convId, msg.id)
    else reacts.like(msg.convId, msg.id)
  }

  private def statusString(timestamp: String, m: MessageData, convType: ConversationType) =
    m.state match {
      case Status.PENDING => getString(R.string.message_footer__status__sending)
      case Status.SENT => getString(R.string.message_footer__status__sent, timestamp)
      case Status.DELIVERED if convType == ConversationType.Group => context.getString(R.string.message_footer__status__sent, timestamp)
      case Status.DELIVERED => context.getString(R.string.message_footer__status__delivered, timestamp)
      case Status.DELETED => getString(R.string.message_footer__status__deleted, timestamp)
      case Status.FAILED |
           Status.FAILED_READ => context.getString(R.string.message_footer__status__failed)
      case _ => timestamp
    }

  private def ephemeralTimeoutString(timestamp: String, remaining: FiniteDuration) = {
    val stringBuilder = new StringBuilder
    if (remaining > 1.day) {
      val days = remaining.toDays.toInt
      stringBuilder.append(context.getResources.getQuantityString(R.plurals.message_footer__expire__days, days, new Integer(days))).append(", ")
    }
    if (remaining > 1.hour) {
      val hours = remaining.toHours.toInt % 24
      stringBuilder.append(context.getResources.getQuantityString(R.plurals.message_footer__expire__hours, hours, new Integer(hours))).append(", ")
    }
    if (remaining > 1.minute) {
      val minutes = remaining.toMinutes.toInt % 60
      stringBuilder.append(context.getResources.getQuantityString(R.plurals.message_footer__expire__minutes, minutes, new Integer(minutes))).append(", ")
    }
    val seconds = remaining.toSeconds.toInt % 60
    stringBuilder.append(context.getResources.getQuantityString(R.plurals.message_footer__expire__seconds, seconds, new Integer(seconds)))
    context.getString(R.string.message_footer__status__ephemeral_summary, timestamp, stringBuilder.toString)
  }
}

object FooterViewController {
  val FocusTimeout = 3.seconds
}
