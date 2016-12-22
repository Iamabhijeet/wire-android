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

import com.waz.ZLog._
import com.waz.api.{IConversation, Message}
import com.waz.bitmap.BitmapUtils
import com.waz.content.MessagesStorage
import com.waz.model.MessageData.MessageDataDao
import com.waz.model.{AssetData, AssetId, ConvId}
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.assets.AssetService.BitmapResult.BitmapLoaded
import com.waz.service.images.BitmapSignal
import com.waz.threading.SerialDispatchQueue
import com.waz.ui.MemoryImageCache.BitmapRequest.Single
import com.waz.utils.events.Signal
import com.waz.zclient.{Injectable, Injector}

import scala.concurrent.Future

//testable!
protected class CollectionController(implicit injector: Injector) extends Injectable {

  private implicit val tag: LogTag = logTagFor[CollectionController]

  private implicit val dispatcher = new SerialDispatchQueue(name = "CollectionController")

  val zms = inject[Signal[ZMessaging]]

  val currentConv = zms.flatMap(_.convsStats.selectedConversationId).collect { case Some(id) => id }

  val msgStorage = zms.map(_.messagesStorage)

  val assetStorage = zms.map(_.assetsStorage)

  val singleImage = Signal[Option[AssetId]](None)

  def messagesByType(tpe: CollectionController.Type, limit: Int = 0) = (for {
    msgs <- msgStorage
    convId <- currentConv
  } yield {
    (msgs, convId)
  }).flatMap { case (msgs, convId) =>
    Signal.future(Future.sequence(tpe.msgTypes.map(t => loadMessagesByType(convId, msgs, limit, t))).map(_.flatten))
  }

  def assetSignal(id: AssetId) = assetStorage.flatMap(_.signal(id))

  val conversation = zms.zip(currentConv) flatMap { case (zms, convId) => zms.convsStorage.signal(convId) }

  val conversationName = conversation map (data => if (data.convType == IConversation.Type.GROUP) data.name.filter(!_.isEmpty).getOrElse(data.generatedName) else data.generatedName)

  //TODO - consider making messageType a Seq and passing that logic down to SE - (if we don't use a cursor)
  private def loadMessagesByType(conv: ConvId, storage: MessagesStorage, limit: Int, messageType: Message.Type) = {
    storage.find(m => m.convId == conv && m.msgType == messageType && !m.isEphemeral, MessageDataDao.findByType(conv, messageType)(_), identity).map(results => results.sortBy(_.time).reverse.take(if (limit > 0) limit else results.length))
  }

  private def loadBitmap(assetId: AssetId, width: Int)  = zms.flatMap { zms =>
    zms.assetsStorage.signal(assetId).flatMap {
      case data@AssetData.IsImage() => BitmapSignal(data, Single(width), zms.imageLoader, zms.imageCache)
      case _ => Signal.empty[BitmapResult]
    }
  }

  def bitmapSquareSignal(assetId: AssetId, width: Int) = loadBitmap(assetId: AssetId, width: Int).map {
    case BitmapLoaded(bmp, _) => Option(BitmapUtils.cropRect(bmp, width))
    case _ => None
  }

  def bitmapSignal(assetId: AssetId, width: Int) = loadBitmap(assetId: AssetId, width: Int).map {
    case BitmapLoaded(bmp, _) => Option(bmp)
    case _ => None
  }

}

object CollectionController {

  trait Type {
    val msgTypes: Seq[Message.Type]
  }

  case object Links extends Type {
    override val msgTypes = Seq(Message.Type.RICH_MEDIA)
  }

  case object Images extends Type {
    override val msgTypes = Seq(Message.Type.ASSET)
  }

  //Now we can add more types to this sequence for the "others" category
  case object Files extends Type {
    override val msgTypes = Seq(Message.Type.ANY_ASSET)
  }

  case object All extends Type {
    //feels a little bit messy... maybe think of a neater way to represent the types
    override val msgTypes = Images.msgTypes ++ Files.msgTypes
  }
}
