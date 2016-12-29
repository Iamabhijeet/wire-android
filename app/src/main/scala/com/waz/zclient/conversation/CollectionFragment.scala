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

import android.content.Context
import android.os.Bundle
import android.support.v4.app.FragmentManager
import android.support.v7.widget.{GridLayoutManager, LinearLayoutManager, RecyclerView, Toolbar}
import android.util.Patterns
import android.view.View.{OnClickListener, OnTouchListener}
import android.view.{LayoutInflater, MotionEvent, View, ViewGroup}
import android.widget.TextView
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.model.{AssetId, MessageData}
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.controllers.collections.CollectionsObserver
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversation.collections.CollectionItemDecorator
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.{FragmentHelper, MainActivity, OnBackPressedListener, R}
import org.threeten.bp.{LocalDateTime, ZoneId}

class CollectionFragment extends BaseFragment[CollectionFragment.Container] with FragmentHelper with OnBackPressedListener with CollectionsObserver  {

  private implicit lazy val context: Context = getContext

  private implicit val tag: LogTag = logTagFor[CollectionFragment]

  lazy val controller = getControllerFactory.getCollectionsController
  var adapter: CollectionAdapter = null


  override def onStart(): Unit = {
    super.onStart()
    controller.addObserver(this)
  }


  override def onStop(): Unit = {
    super.onStop()
    controller.removeObserver(this)
  }

  private def showSingleImage(assetId: AssetId) = {
    getChildFragmentManager.findFragmentByTag(SingleImageCollectionFragment.TAG) match {
      case null => getChildFragmentManager.beginTransaction.add(R.id.fl__collection_content, SingleImageCollectionFragment.newInstance(assetId), SingleImageCollectionFragment.TAG).addToBackStack(SingleImageCollectionFragment.TAG).commit
      case fragment: SingleImageCollectionFragment => fragment.setAsset(assetId)
      case _ =>
    }
  }

  private def closeSingleImage() = {
    getChildFragmentManager.findFragmentByTag(SingleImageCollectionFragment.TAG) match {
      case null =>
      case _ => getChildFragmentManager.popBackStackImmediate(SingleImageCollectionFragment.TAG, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }
  }

  private def openUrl(messageData: MessageData): Unit = {
    val url = extractUrl(messageData)
    getActivity match {
      case ma: MainActivity => ma.onOpenUrl(url)
      case _ =>
    }
  }

  private def extractUrl(messageData: MessageData): String = {
    if (messageData.content.exists(_.openGraph.nonEmpty)) {
      messageData.content.find(_.tpe == Message.Part.Type.WEB_LINK).map(_.content).getOrElse("")
    } else {
      messageData.content.find(content => content.tpe == Message.Part.Type.TEXT && Patterns.WEB_URL.matcher(content.content).matches()).map(_.content).getOrElse("")
    }
  }

  private def textIdForContentMode(contentMode: Int) = contentMode match {
    case CollectionAdapter.VIEW_MODE_IMAGES => R.string.library_header_pictures
    case CollectionAdapter.VIEW_MODE_FILES => R.string.library_header_files
    case CollectionAdapter.VIEW_MODE_LINKS => R.string.library_header_links
    case _ => R.string.library_header_all
  }

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_collection, container, false)
    val name: TextView  = ViewUtils.getView(view, R.id.tv__collection_toolbar__name)
    val contentView: TextView = ViewUtils.getView(view, R.id.tv__collection_toolbar__content)
    val recyclerView: RecyclerView = ViewUtils.getView(view, R.id.rv__collection)
    val emptyView: View = ViewUtils.getView(view, R.id.ll__collection__empty)
    emptyView.setVisibility(View.GONE)

    controller.focusedItem.on(Threading.Ui) {
      case Some(md) if md.msgType == Message.Type.ASSET => showSingleImage(md.assetId)
      case Some(md) if md.msgType == Message.Type.RICH_MEDIA => openUrl(md); controller.focusedItem ! None
      case _ => closeSingleImage()
    }

    val columns = 4
    adapter = new CollectionAdapter(ViewUtils.getRealDisplayWidth(context), columns, controller)

    Signal(adapter.adapterState, controller.focusedItem, controller.conversationName).on(Threading.Ui) {
      case ((_, _), Some(messageData), conversationName) =>
        name.setText(LocalDateTime.ofInstant(messageData.time, ZoneId.systemDefault()).toLocalDate.toString)
        contentView.setText(conversationName)
      case ((contentMode, 0), None, conversationName) =>
        emptyView.setVisibility(View.VISIBLE)
        recyclerView.setVisibility(View.GONE)
        contentView.setText(textIdForContentMode(contentMode))
        name.setText(conversationName)
      case ((contentMode, _), None, conversationName) =>
        emptyView.setVisibility(View.GONE)
        recyclerView.setVisibility(View.VISIBLE)
        contentView.setText(textIdForContentMode(contentMode))
        name.setText(conversationName)
      case _ =>
    }

    val collectionItemDecorator = new CollectionItemDecorator(adapter, columns)
    recyclerView.setAdapter(adapter)
    recyclerView.setOnTouchListener(new OnTouchListener {
      var headerDown = false

      override def onTouch(v: View, event: MotionEvent): Boolean = {
        val x = Math.round(event.getX)
        val y = Math.round(event.getY)
        event.getAction match {
          case MotionEvent.ACTION_DOWN =>
            if (collectionItemDecorator.getHeaderClicked(x, y) < 0) {
              headerDown = false
            } else {
              headerDown = true
            }
            false
          case MotionEvent.ACTION_MOVE =>
            if (event.getHistorySize > 0) {
              val deltaX = event.getHistoricalX(0) - x
              val deltaY = event.getHistoricalY(0) - y
              if (Math.abs(deltaY) + Math.abs(deltaX) > CollectionFragment.MAX_DELTA_TOUCH) {
                headerDown = false
              }
            }
            false
          case MotionEvent.ACTION_UP =>
            if (!headerDown) {
              return false
            }
            adapter.onHeaderClicked(collectionItemDecorator.getHeaderClicked(x, y))
          case _ => false;
        }
      }
    })
    recyclerView.addItemDecoration(collectionItemDecorator)
    val layoutManager = new GridLayoutManager(context, columns, LinearLayoutManager.VERTICAL, false)
    layoutManager.setSpanSizeLookup(new CollectionSpanSizeLookup(columns, adapter))
    recyclerView.setLayoutManager(layoutManager)

    val toolbar: Toolbar = ViewUtils.getView(view, R.id.t_collection_toolbar)
    toolbar.setNavigationOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = onBackPressed
    })

    view
  }

  override def onBackPressed(): Boolean = {
    getChildFragmentManager.findFragmentByTag(SingleImageCollectionFragment.TAG) match {
      case fragment: SingleImageCollectionFragment => controller.focusedItem ! None; return true
      case _ =>
    }
    if (!adapter.onBackPressed)
      getControllerFactory.getCollectionsController.closeCollection
    true
  }

  override def openCollection(): Unit = {}

  override def forwardCollectionMessage(message: Message): Unit = {}

  override def previousItemRequested(): Unit =
    controller.focusedItem mutate {
      case Some(messageData) => Some(adapter.getPreviousItem(messageData).getOrElse(messageData))
      case _ => None
    }

  override def nextItemRequested(): Unit =
    controller.focusedItem mutate {
      case Some(messageData) => Some(adapter.getNextItem(messageData).getOrElse(messageData))
      case _ => None
    }

  override def closeCollection(): Unit = {}
}

object CollectionFragment {

  val TAG = CollectionFragment.getClass.getSimpleName

  val MAX_DELTA_TOUCH = 30

  def newInstance() = new CollectionFragment

  trait Container

}
