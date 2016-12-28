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

import java.util
import java.util.concurrent.CopyOnWriteArraySet

import android.graphics.Bitmap
import com.waz.ZLog._
import com.waz.api.{IConversation, Message, impl}
import com.waz.bitmap.BitmapUtils
import com.waz.content.MessagesStorage
import com.waz.model.MessageData.MessageDataDao
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.assets.AssetService.BitmapResult.BitmapLoaded
import com.waz.service.images.BitmapSignal
import com.waz.threading.SerialDispatchQueue
import com.waz.ui.MemoryImageCache.BitmapRequest.Single
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.zclient.controllers.collections.CollectionsObserver
import com.waz.zclient.conversation.CollectionController.Type
import com.waz.zclient.{Injectable, Injector}

import scala.concurrent.Future

trait ICollectionsController {

  val focusedItem: SourceSignal[Option[MessageData]]
  val conversationName: Signal[String]

  def messagesByType(`type`: CollectionController.Type, limit: Int = 0): Signal[Seq[MessageData]]

  def assetSignal(id: AssetId): Signal[AssetData]

  def userSignal(id: UserId): Signal[UserData]

  def bitmapSignal(assetId: AssetId, width: Int): Signal[Option[Bitmap]]

  def bitmapSquareSignal(assetId: AssetId, width: Int): Signal[Option[Bitmap]]

  def openCollection(): Unit

  def closeCollection(): Unit

  def requestPreviousItem(): Unit

  def requestNextItem(): Unit

  def shareMessageData(messageData: MessageData): Unit

  def addObserver(collectionsObserver: CollectionsObserver): Unit

  def removeObserver(collectionsObserver: CollectionsObserver): Unit
}

class CollectionController(implicit injector: Injector) extends Injectable with ICollectionsController {

  private implicit val tag: LogTag = logTagFor[CollectionController]

  private implicit val dispatcher = new SerialDispatchQueue(name = "CollectionController")

  val zms = inject[Signal[ZMessaging]]

  val currentConv = zms.flatMap(_.convsStats.selectedConversationId).collect { case Some(id) => id }

  val msgStorage = zms.map(_.messagesStorage)

  val assetStorage = zms.map(_.assetsStorage)

  private val observers: java.util.Set[CollectionsObserver] = new util.HashSet[CollectionsObserver]

  override def messagesByType(tpe: CollectionController.Type, limit: Int = 0) = (for {
    msgs <- msgStorage
    convId <- currentConv
  } yield {
    (msgs, convId)
  }).flatMap { case (msgs, convId) =>
    Signal.future(Future.sequence(tpe.msgTypes.map(t => loadMessagesByType(convId, msgs, limit, t))).map(_.flatten))
  }

  override def assetSignal(id: AssetId) = assetStorage.flatMap(_.signal(id))

  override def userSignal(id: UserId) = zms.map(_.usersStorage).flatMap(_.signal(id))

  val conversation = zms.zip(currentConv) flatMap { case (zms, convId) => zms.convsStorage.signal(convId) }

  override val conversationName = conversation map (data => if (data.convType == IConversation.Type.GROUP) data.name.filter(!_.isEmpty).getOrElse(data.generatedName) else data.generatedName)

  override val focusedItem: SourceSignal[Option[MessageData]] = Signal(None)

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

  override def bitmapSquareSignal(assetId: AssetId, width: Int) = loadBitmap(assetId: AssetId, width: Int).map {
    case BitmapLoaded(bmp, _) => Option(BitmapUtils.cropRect(bmp, width))
    case _ => None
  }

  override def bitmapSignal(assetId: AssetId, width: Int) = loadBitmap(assetId: AssetId, width: Int).map {
    case BitmapLoaded(bmp, _) => Option(bmp)
    case _ => None
  }

  private def performOnObservers(func: (CollectionsObserver) => Unit) = {
    val collectionObservers: CopyOnWriteArraySet[CollectionsObserver] = new CopyOnWriteArraySet[CollectionsObserver](observers)
    import scala.collection.JavaConversions._
    for (observer <- collectionObservers) {
      func(observer)
    }
  }

  override def openCollection = performOnObservers(_.openCollection())

  override def closeCollection = performOnObservers(_.closeCollection())

  override def requestPreviousItem(): Unit = performOnObservers(_.previousItemRequested())

  override def requestNextItem(): Unit = performOnObservers(_.nextItemRequested())

  override def shareMessageData(messageData: MessageData): Unit = performOnObservers(_.forwardCollectionMessage(new impl.Message(messageData.id, messageData, IndexedSeq(), false)(ZMessaging.currentUi)))

  override def addObserver(collectionsObserver: CollectionsObserver): Unit = observers.add(collectionsObserver)

  override def removeObserver(collectionsObserver: CollectionsObserver): Unit = observers.remove(collectionsObserver)
}

class StubCollectionController extends ICollectionsController{

  override val focusedItem: SourceSignal[Option[MessageData]] = Signal(None)
  override val conversationName: Signal[String] = Signal("")

  override def messagesByType(`type`: Type, limit: Int): Signal[Seq[MessageData]] = Signal(Seq())

  override def openCollection: Unit = {}

  override def assetSignal(id: AssetId): Signal[AssetData] = Signal.empty

  override def userSignal(id: UserId): Signal[UserData] = Signal.empty

  override def bitmapSignal(assetId: AssetId, width: Int): Signal[Option[Bitmap]] = Signal(None)

  override def bitmapSquareSignal(assetId: AssetId, width: Int): Signal[Option[Bitmap]] = Signal(None)

  override def closeCollection: Unit = {}

  override def requestPreviousItem(): Unit = {}

  override def requestNextItem(): Unit = {}

  override def shareMessageData(messageData: MessageData): Unit = {}

  override def addObserver(collectionsObserver: CollectionsObserver): Unit = {}

  override def removeObserver(collectionsObserver: CollectionsObserver): Unit = {}
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
    override val msgTypes = Images.msgTypes ++ Files.msgTypes ++ Links.msgTypes
  }
}
