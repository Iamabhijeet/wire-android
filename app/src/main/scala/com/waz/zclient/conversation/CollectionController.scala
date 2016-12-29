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

import com.waz.ZLog._
import com.waz.api.{IConversation, Message}
import com.waz.content.MessagesStorage
import com.waz.model.MessageData.MessageDataDao
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.zclient.controllers.collections.CollectionsObserver
import com.waz.zclient.conversation.CollectionController.Type
import com.waz.zclient.{Injectable, Injector}

import scala.concurrent.Future

trait ICollectionsController {

  val focusedItem: SourceSignal[Option[MessageData]]
  val conversationName: Signal[String]

  def messagesByType(`type`: CollectionController.Type, limit: Int = 0): Signal[Seq[MessageData]]

  def openCollection(): Unit

  def closeCollection(): Unit

  def requestPreviousItem(): Unit

  def requestNextItem(): Unit

  def openShareCollectionItem(messageData: MessageData): Unit

  def closeShareCollectionItem(): Unit

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

  val conversation = zms.zip(currentConv) flatMap { case (zms, convId) => zms.convsStorage.signal(convId) }

  override val conversationName = conversation map (data => if (data.convType == IConversation.Type.GROUP) data.name.filter(!_.isEmpty).getOrElse(data.generatedName) else data.generatedName)

  override val focusedItem: SourceSignal[Option[MessageData]] = Signal(None)

  //TODO - consider making messageType a Seq and passing that logic down to SE - (if we don't use a cursor)
  private def loadMessagesByType(conv: ConvId, storage: MessagesStorage, limit: Int, messageType: Message.Type) = {
    storage.find(m => m.convId == conv && m.msgType == messageType && !m.isEphemeral, MessageDataDao.findByType(conv, messageType)(_), identity).map(results => results.sortBy(_.time).reverse.take(if (limit > 0) limit else results.length))
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

  override def openShareCollectionItem(messageData: MessageData): Unit = performOnObservers(_.shareCollectionItem(messageData))

  override def closeShareCollectionItem(): Unit = {performOnObservers(_.closeCollectionShare())}

  override def addObserver(collectionsObserver: CollectionsObserver): Unit = observers.add(collectionsObserver)

  override def removeObserver(collectionsObserver: CollectionsObserver): Unit = observers.remove(collectionsObserver)
}

class StubCollectionController extends ICollectionsController{

  override val focusedItem: SourceSignal[Option[MessageData]] = Signal(None)
  override val conversationName: Signal[String] = Signal("")

  override def messagesByType(`type`: Type, limit: Int): Signal[Seq[MessageData]] = Signal(Seq())

  override def openCollection: Unit = {}

  override def closeCollection: Unit = {}

  override def requestPreviousItem(): Unit = {}

  override def requestNextItem(): Unit = {}

  override def openShareCollectionItem(messageData: MessageData): Unit = {}

  override def closeShareCollectionItem(): Unit = {}

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
