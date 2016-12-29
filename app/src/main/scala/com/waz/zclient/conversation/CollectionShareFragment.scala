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

import android.app.ProgressDialog
import android.content.{Context, DialogInterface}
import android.database.DataSetObserver
import android.net.Uri
import android.os.Bundle
import android.support.v7.app.AlertDialog
import android.text.TextUtils
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.RadioGroup.OnCheckedChangeListener
import android.widget._
import com.waz.api.ConversationsList.ConversationsListState
import com.waz.api._
import com.waz.model.{AssetData, AssetId, AssetType, MessageId}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.controllers.AssetsController
import com.waz.zclient.controllers.sharing.SharedContentType
import com.waz.zclient.core.stores.conversation.{ConversationChangeRequester, ConversationStoreObserver}
import com.waz.zclient.messages.MessagesController
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R}
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.ui.text.{GlyphTextView, TypefaceTextView}
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.ViewUtils

import scala.collection.immutable.HashSet


class CollectionShareFragment extends BaseFragment[CollectionFragment.Container] with FragmentHelper with OnBackPressedListener {

  lazy val assetsController = inject[AssetsController]

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_collection_share, container, false)
    val listView = ViewUtils.getView(view, R.id.lv__conversation_list).asInstanceOf[ListView]
    val sendButton = ViewUtils.getView(view, R.id.gtv__share_button).asInstanceOf[GlyphTextView]
    val adapter = new CollectionShareConversationAdapter(getContext)
    listView.setAdapter(adapter)
    getStoreFactory.getConversationStore.addConversationStoreObserverAndUpdate(adapter)

    val assetId = getArguments.getString(CollectionShareFragment.ASSET_ARG)
    val messageContent = getArguments.getString(CollectionShareFragment.TEXT_ARG)

    sendButton.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        if (adapter.selectedConversations.isEmpty) {
          return
        }
        if (!TextUtils.isEmpty(assetId)) {
          val dialog = ProgressDialog.show(getContext,
            getString(R.string.conversation__action_mode__fwd__dialog__title),
            getString(R.string.conversation__action_mode__fwd__dialog__message), true, true, null)
          assetsController.assetSignal(AssetId(assetId)).map(asset => (asset._1.assetType, asset._1.source)).on(Threading.Ui){
            case (Some(AssetType.Image), Some(uri)) =>
              dialog.dismiss()
              sendImageAsset(uri, adapter.selectedConversations.toSet)
              getControllerFactory.getCollectionsController.closeShareCollectionItem()
            case (Some(_), Some(uri)) =>
              dialog.dismiss()
              sendAsset(uri, adapter.selectedConversations.toSet)
              getControllerFactory.getCollectionsController.closeShareCollectionItem()
            case _ => dialog.dismiss()
          }
        } else if (!TextUtils.isEmpty(messageContent)) {
          sendMessage(messageContent, adapter.selectedConversations.toSet)
          getControllerFactory.getCollectionsController.closeShareCollectionItem()
        }
      }
    })
    view
  }

  //TODO this was copied from ConversationFragment...
  private val assetErrorHandler: MessageContent.Asset.ErrorHandler = new MessageContent.Asset.ErrorHandler() {
    def noWifiAndFileIsLarge(sizeInBytes: Long, net: NetworkMode, answer: MessageContent.Asset.Answer) {
      if (getActivity == null) {
        answer.ok()
        return
      }
      val dialog: AlertDialog = ViewUtils.showAlertDialog(getActivity, R.string.asset_upload_warning__large_file__title, R.string.asset_upload_warning__large_file__message_default, R.string.asset_upload_warning__large_file__button_accept, R.string.asset_upload_warning__large_file__button_cancel, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, which: Int) {
          answer.ok()
        }
      }, new DialogInterface.OnClickListener() {
        def onClick(dialog: DialogInterface, which: Int) {
          answer.cancel()
        }
      })
      dialog.setCancelable(false)
      if (sizeInBytes > 0) {
        val fileSize: String = Formatter.formatFileSize(getContext, sizeInBytes)
        dialog.setMessage(getString(R.string.asset_upload_warning__large_file__message, fileSize))
      }
    }
  }

  private def sendAsset(assetUri: Uri, conversations: Set[IConversation]): Unit = {
    conversations.foreach{
      conversation =>
        getStoreFactory.getConversationStore.sendMessage(conversation, AssetFactory.fromContentUri(assetUri), assetErrorHandler)
    }
  }

  private def sendImageAsset(assetUri: Uri, conversations: Set[IConversation]): Unit = {
    conversations.foreach{
      conversation =>
        getStoreFactory.getConversationStore.sendMessage(conversation, ImageAssetFactory.getImageAsset(assetUri))
    }
  }

  private def sendMessage(content: String, conversations: Set[IConversation]): Unit = {
    conversations.foreach{
      conversation =>
        getStoreFactory.getConversationStore.sendMessage(conversation, content)
    }
  }

  override def onBackPressed(): Boolean = {
    getControllerFactory.getCollectionsController.closeShareCollectionItem()
    true
  }
}

class CollectionShareConversationAdapter(context: Context) extends ListAdapter with ConversationStoreObserver{

  var conversationsList: Option[ConversationsList] = None
  val selectedConversations = new scala.collection.mutable.HashSet[IConversation]
  private val checkBoxListener = new CompoundButton.OnCheckedChangeListener {
    override def onCheckedChanged(buttonView: CompoundButton, isChecked: Boolean): Unit = {
      if (isChecked) {
        selectedConversations.add(buttonView.getTag.asInstanceOf[IConversation])
      } else {
        selectedConversations.remove(buttonView.getTag.asInstanceOf[IConversation])
      }
    }
  }

  override def isEnabled(position: Int): Boolean = {
    false
  }

  override def areAllItemsEnabled(): Boolean = false

  override def getItemId(position: Int): Long = position

  override def getCount: Int = conversationsList.map(_.size()).getOrElse(0)

  override def getView(position: Int, convertView: View, parent: ViewGroup): View = {
    var view = convertView
    if (convertView == null) {
      view = new SelectableConversationRow(context, checkBoxListener)
    }
    view.asInstanceOf[SelectableConversationRow].setConversation(getItem(position), selectedConversations.contains(getItem(position)))
    view
  }

  override def registerDataSetObserver(observer: DataSetObserver): Unit = {}

  override def getItem(position: Int): IConversation = conversationsList.get.get(position)

  override def unregisterDataSetObserver(observer: DataSetObserver): Unit = {}

  override def getViewTypeCount: Int = 1

  override def getItemViewType(position: Int): Int = 1

  override def isEmpty: Boolean = conversationsList.exists(_.size() > 0)

  override def hasStableIds: Boolean = true

  override def onConversationListUpdated(conversationsList: ConversationsList): Unit = {
    this.conversationsList = Some(conversationsList)
  }

  override def onVerificationStateChanged(conversationId: String, previousVerification: Verification, currentVerification: Verification): Unit = {}

  override def onCurrentConversationHasChanged(fromConversation: IConversation, toConversation: IConversation, conversationChangerSender: ConversationChangeRequester): Unit = {}

  override def onMenuConversationHasChanged(fromConversation: IConversation): Unit = {}

  override def onConversationListStateHasChanged(state: ConversationsListState): Unit = {}

  override def onConversationSyncingStateHasChanged(syncState: SyncState): Unit = {}
}

class SelectableConversationRow(context: Context, attrs: AttributeSet, defStyleAttr: Int, checkBoxListener: CompoundButton.OnCheckedChangeListener) extends LinearLayout(context, attrs, defStyleAttr) {
  def this(context: Context, attrs: AttributeSet, checkBoxListener: CompoundButton.OnCheckedChangeListener) = this(context, attrs, 0, checkBoxListener)
  def this(context: Context, checkBoxListener: CompoundButton.OnCheckedChangeListener) =  this(context, null, checkBoxListener)

  LayoutInflater.from(context).inflate(R.layout.row_selectable_conversation, this, true)
  val nameView = ViewUtils.getView(this, R.id.ttv__conversation_name).asInstanceOf[TypefaceTextView]
  val checkBox = ViewUtils.getView(this, R.id.rb__conversation_selected).asInstanceOf[CheckBox]
  checkBox.setOnCheckedChangeListener(checkBoxListener)

  def setConversation(conversation: IConversation, checked: Boolean): Unit = {
    nameView.setText(conversation.getName)
    checkBox.setTag(conversation)
    checkBox.setChecked(checked)
  }
}

object CollectionShareFragment {
  val TAG = CollectionShareFragment.getClass.getSimpleName

  val ASSET_ARG = "ASSET_ARG"
  val TEXT_ARG = "TEXT_ARG"

  def newInstance(assetId: AssetId): CollectionShareFragment = {
    val fragment = new CollectionShareFragment
    val bundle = new Bundle()
    bundle.putString(ASSET_ARG, assetId.str)
    fragment.setArguments(bundle)
    fragment
  }

  def newInstance(messageContent: String): CollectionShareFragment ={
    val fragment = new CollectionShareFragment
    val bundle = new Bundle()
    bundle.putString(TEXT_ARG, messageContent)
    fragment.setArguments(bundle)
    fragment
  }
}
