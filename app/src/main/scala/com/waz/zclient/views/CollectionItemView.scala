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
package com.waz.zclient.views

import android.content.Context
import android.support.v7.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.{FrameLayout, LinearLayout, TextView}
import com.waz.model.{ConversationData, MessageContent, MessageData}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal, SourceSignal}
import com.waz.zclient.messages.MessageView.MsgBindOptions
import com.waz.zclient.messages.MessageViewPart
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.{R, ViewHelper}
import org.threeten.bp.{LocalDateTime, ZoneId}

class CollectionItemView(context: Context, attrs: AttributeSet, style: Int) extends LinearLayout(context, attrs, style) with ViewHelper {
  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  val zms = inject[Signal[ZMessaging]]
  inflate(R.layout.row_collection_item_view)

  val container: FrameLayout = ViewUtils.getView(this, R.id.fl__collections__content_layout)
  val messageTime: TextView = ViewUtils.getView(this, R.id.ttv__collection_item__time)
  val messageUser: TextView = ViewUtils.getView(this, R.id.ttv__collection_item__user_name)

  val messageData: SourceSignal[MessageData] = Signal()

  messageData.flatMap(msg => zms.map(_.usersStorage).flatMap(_.signal(msg.userId))).on(Threading.Ui) {
    user => messageUser.setText(user.name)
  }

  messageData.on(Threading.Ui) {
    md => messageTime.setText(LocalDateTime.ofInstant(md.time, ZoneId.systemDefault()).toLocalDate.toString)
  }

  def inflateContent(contentId: Int) = LayoutInflater.from(getContext).inflate(contentId, container, true)

  def setMessageData(messageData: MessageData, content: Option[MessageContent]): Unit = {
    this.messageData ! messageData
    val messageViewPart = container.getChildAt(0).asInstanceOf[MessageViewPart]
    if (messageViewPart != null) {
      messageViewPart.set(messageData, content, CollectionItemView.DefaultBindingOptions) }
  }
}

object CollectionItemView{
  val DefaultBindingOptions = MsgBindOptions(0, false, false, false, false, 0, ConversationData.ConversationType.Unknown)
}

abstract class CollectionItemViewHolder(view: CollectionItemView)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view){

  def setMessageData(messageData: MessageData, content: Option[MessageContent]): Unit = {
    view.setTag(messageData)
    view.setMessageData(messageData, content)
  }

  def setMessageData(messageData: MessageData): Unit = {
    setMessageData(messageData, None)
  }
}

case class FileViewHolder(view: CollectionItemView)(implicit eventContext: EventContext) extends CollectionItemViewHolder(view)

case class LinkPreviewViewHolder(view: CollectionItemView)(implicit eventContext: EventContext) extends CollectionItemViewHolder(view) {
  override def setMessageData(messageData: MessageData): Unit = {
    val content = messageData.content.find(_.openGraph.nonEmpty)
    setMessageData(messageData, content)
  }
}

case class SimpleLinkViewHolder(view: CollectionItemView)(implicit eventContext: EventContext) extends CollectionItemViewHolder(view)
