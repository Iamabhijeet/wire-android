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
package com.waz.zclient.messages

import java.util

import android.view.ViewGroup
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.ConvId
import com.waz.model.ConversationData.ConversationType
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.zclient.controllers.global.SelectionController
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.MessagesListView.UnreadIndex
import com.waz.zclient.messages.RecyclerCursor.RecyclerNotifier
import com.waz.zclient.{Injectable, Injector}

class MessagesListAdapter(listWidth: Signal[Int])(implicit inj: Injector, ec: EventContext)
  extends MessagesListView.Adapter() with Injectable { adapter =>

  verbose("MessagesListAdapter created")

  val zms = inject[Signal[ZMessaging]]
  val listController = inject[MessagesController]
  val selectedConversation = inject[SelectionController].selectedConv

  var unreadIndex = UnreadIndex(0)

  val conv = for {
    zs <- zms
    convId <- selectedConversation
    conv <- Signal future zs.convsStorage.get(convId)
  } yield conv

  val cursor = for {
    zs <- zms
    Some(c) <- conv
  } yield
    (new RecyclerCursor(c.id, zs, notifier), c.convType)

  private var messages = Option.empty[RecyclerCursor]
  private var convId = ConvId()
  private var convType = ConversationType.Group

  cursor.on(Threading.Ui) { case (c, tpe) =>
    if (!messages.contains(c)) {
      verbose(s"cursor changed: ${c.count}")
      messages.foreach(_.close())
      messages = Some(c)
      convType = tpe
      convId = c.conv
      notifier.notifyDataSetChanged()
    }
  }

  override def getConvId: ConvId = convId

  override def getUnreadIndex = unreadIndex

  override def getItemCount: Int = messages.fold(0)(_.count)

  def message(position: Int) = messages.get.apply(position)

  def lastReadIndex = messages.fold(-1)(_.lastReadIndex())

  override def getItemViewType(position: Int): Int = MessageView.viewType(message(position).message.msgType)

  override def onBindViewHolder(holder: MessageViewHolder, pos: Int): Unit = {
    onBindViewHolder(holder, pos, new util.ArrayList[AnyRef])
  }

  override def onBindViewHolder(holder: MessageViewHolder, pos: Int, payloads: util.List[AnyRef]): Unit = {
    verbose(s"onBindViewHolder: position: $pos")
    val data = message(pos)
    val isLast = pos == adapter.getItemCount
    val isSelf = zms.currentValue.exists(_.selfUserId == data.message.userId)
    val isFirstUnread = pos > 0 && !isSelf && unreadIndex.index == pos
    val isLastSelf = listController.isLastSelf(data.message.id)
    val opts = MsgBindOptions(pos, isSelf, isLast, isLastSelf, isFirstUnread = isFirstUnread, listWidth.currentValue.getOrElse(0), convType)

    holder.bind(data, if (pos == 0) None else Some(message(pos - 1).message), opts)
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder =
    MessageViewHolder(MessageView(parent, viewType), adapter)

  val notifier = new RecyclerNotifier {
    // view depends on two message entries,
    // most importantly, view needs to be refreshed if previous message was added or removed

    private def notifyChangedIfExists(position: Int) =
      if (position >= 0 && position < getItemCount)
        adapter.notifyItemChanged(position)

    override def notifyItemMoved(src: Int, dst: Int) = {
      adapter.notifyItemMoved(src, dst)

      notifyChangedIfExists(if (src < dst) src else src + 1)
      notifyChangedIfExists(dst + 1)
    }

    override def notifyItemInserted(pos: Int) = {
      adapter.notifyItemInserted(pos)

      notifyChangedIfExists(pos + 1)
    }

    override def notifyItemRemoved(pos: Int) =
      notifyItemRangeRemoved(pos, 1)

    override def notifyItemChanged(pos: Int) =
      adapter.notifyItemChanged(pos)

    override def notifyItemRangeRemoved(pos: Int, count: Int) = {
      adapter.notifyItemRangeRemoved(pos, count)
      notifyChangedIfExists(pos)
    }

    override def notifyDataSetChanged() = {
      unreadIndex = UnreadIndex(lastReadIndex + 1)
      adapter.notifyDataSetChanged()
    }
  }

}
