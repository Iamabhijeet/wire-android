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

import java.util.Locale

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.support.v4.content.ContextCompat
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.{AdapterDataObserver, ViewHolder}
import android.text.format.Formatter
import android.util.{AttributeSet, Patterns}
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{FrameLayout, LinearLayout, TextView}
import com.waz.ZLog._
import com.waz.api.{ImageAsset, ImageAssetFactory, Message}
import com.waz.model.{AssetData, AssetId, MessageData}
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.returning
import com.waz.zclient.conversation.CollectionAdapter._
import com.waz.zclient.core.api.scala.ModelObserver
import com.waz.zclient.pages.main.conversation.views.AspectRatioImageView
import com.waz.zclient.ui.drawable.FileDrawable
import com.waz.zclient.ui.utils.ResourceUtils
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.images.ImageAssetView
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.temporal.ChronoUnit
import org.threeten.bp.{Instant, LocalDateTime, ZoneId}

//For now just handling images
class CollectionAdapter(screenWidth: Int, columns: Int, ctrler: ICollectionsController)(implicit context: Context, injector: Injector, eventContext: EventContext) extends RecyclerView.Adapter[ViewHolder] with Injectable {

  private implicit val tag: LogTag = logTagFor[CollectionAdapter]

  /**
    * If signals don't have any subscribers, then by default they don't bother computing their values whenever changes are published to them,
    * until they get their first subscriber. If we then try to call Signal#getCurrentValue on such a signal, we'll probably get None or something undefined.
    * There are two ways around this, either call Signal#disableAutoWiring on any signals you wish to be able to access, or have a temporary var that keeps
    * track of the current value, and set listeners to update that var.
    *
    * I'm starting to prefer the second way, as it's a little bit more explicit as to what's happening. Both ways should be used cautiously!!
    */

  val all = ctrler.messagesByType(CollectionController.All, 8)
  private var _all = Seq.empty[MessageData]
  all(_all = _)

  val images = ctrler.messagesByType(CollectionController.Images)
  private var _images = Seq.empty[MessageData]
  images(_images = _)

  val files = ctrler.messagesByType(CollectionController.Files)
  private var _files = Seq.empty[MessageData]
  files(_files = _)

  val links = ctrler.messagesByType(CollectionController.Links)
  private var _links = Seq.empty[MessageData]
  links(_links = _)

  var contentMode = CollectionAdapter.VIEW_MODE_ALL

  images.onChanged.on(Threading.Ui) { _ =>
    contentMode match {
      case CollectionAdapter.VIEW_MODE_IMAGES => notifyDataSetChanged()
      case _ =>
    }
  }

  files.onChanged.on(Threading.Ui) { _ =>
    contentMode match {
      case CollectionAdapter.VIEW_MODE_FILES => notifyDataSetChanged()
      case _ =>
    }
  }

  links.onChanged.on(Threading.Ui) { _ =>
    contentMode match {
      case CollectionAdapter.VIEW_MODE_LINKS => notifyDataSetChanged()
      case _ =>
    }
  }

  all.onChanged.on(Threading.Ui) { _ =>
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => notifyDataSetChanged()
      case _ =>
    }
  }

  var header: CollectionHeaderLinearLayout = null
  val adapterState = Signal[(Int, Int)](contentMode, -1)

  registerAdapterDataObserver(new AdapterDataObserver {
    override def onChanged(): Unit = {
      adapterState ! (contentMode, getItemCount)
    }
  })

  override def getItemCount: Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => all.currentValue.map(_.size).getOrElse(0)
      case CollectionAdapter.VIEW_MODE_FILES => files.currentValue.map(_.size).getOrElse(0)
      case CollectionAdapter.VIEW_MODE_IMAGES => images.currentValue.map(_.size).getOrElse(0)
      case CollectionAdapter.VIEW_MODE_LINKS => links.currentValue.map(_.size).getOrElse(0)
      case _ => 0
    }
  }

  override def getItemViewType(position: Int): Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => {
        all.currentValue.getOrElse(Seq.empty)(position).msgType match {
          case Message.Type.ANY_ASSET => CollectionAdapter.VIEW_TYPE_FILE
          case Message.Type.ASSET => CollectionAdapter.VIEW_TYPE_IMAGE
          case Message.Type.RICH_MEDIA if hasOpenGraphData(position) => CollectionAdapter.VIEW_TYPE_LINK_PREVIEW
          case Message.Type.RICH_MEDIA => CollectionAdapter.VIEW_TYPE_SIMPLE_LINK
          case _ => CollectionAdapter.VIEW_TYPE_FILE
        }
      }
      case CollectionAdapter.VIEW_MODE_FILES => CollectionAdapter.VIEW_TYPE_FILE
      case CollectionAdapter.VIEW_MODE_IMAGES => CollectionAdapter.VIEW_TYPE_IMAGE
      case CollectionAdapter.VIEW_MODE_LINKS if hasOpenGraphData(position) => CollectionAdapter.VIEW_TYPE_LINK_PREVIEW
      case CollectionAdapter.VIEW_MODE_LINKS => CollectionAdapter.VIEW_TYPE_SIMPLE_LINK
      case _ => CollectionAdapter.VIEW_TYPE_FILE
    }
  }

  private def hasOpenGraphData(position: Int): Boolean = {
    getItem(position).content.exists(_.openGraph.nonEmpty)
  }

  override def onBindViewHolder(holder: ViewHolder, position: Int): Unit = {
    holder match {
      case f: FileViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _files, position).foreach(a => f.setAsset(a._2))
      case c: CollViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _images, position).foreach(s => c.setAssetMessage(s._1, s._2, ctrler.bitmapSquareSignal, screenWidth / columns, ResourceUtils.getRandomAccentColor(context)))
      case l: LinkPreviewViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _links, position).foreach(a => l.setMessageData(a._1))
      case l: SimpleLinkViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _links, position).foreach(a => l.setMessageData(a._1))
    }
  }

  def onBackPressed(): Boolean = contentMode match {
    case CollectionAdapter.VIEW_MODE_ALL => false
    case _ => {
      contentMode = CollectionAdapter.VIEW_MODE_ALL
      notifyDataSetChanged()
      true
    }
  }

  def onHeaderClicked(position: Int): Boolean = {
    if (position < 0) {
      false
    } else {
      val newMode = contentMode match {
        case CollectionAdapter.VIEW_MODE_ALL => {
          getHeaderId(position) match {
            case Header.mainLinks => CollectionAdapter.VIEW_MODE_LINKS
            case Header.mainImages => CollectionAdapter.VIEW_MODE_IMAGES
            case Header.mainFiles => CollectionAdapter.VIEW_MODE_FILES
          }
        }
        case _ => contentMode
      }
      if (newMode != contentMode) {
        contentMode = newMode
        notifyDataSetChanged()
        true
      } else {
        false
      }
    }
  }

  private def assetSignal(col: Seq[MessageData], pos: Int): Option[(MessageData, Signal[AssetData])] = col.lift(pos).map(m => (m, ctrler.assetSignal(m.assetId)))

  val imageListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      v.getTag match {
        case md: MessageData if contentMode == CollectionAdapter.VIEW_MODE_IMAGES => ctrler.focusedItem ! Some(md)
        case _ =>
      }
    }
  }

  val fileListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      // TODO
    }
  }

  val linkListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      v.getTag match {
        case md: MessageData if contentMode == CollectionAdapter.VIEW_MODE_LINKS => ctrler.focusedItem ! Some(md)
        case _ =>
      }
    }
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    viewType match {
      case CollectionAdapter.VIEW_TYPE_FILE => FileViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_file, parent, false).asInstanceOf[LinearLayout], fileListener)
      case CollectionAdapter.VIEW_TYPE_IMAGE => CollViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_image, parent, false).asInstanceOf[AspectRatioImageView], imageListener)
      case CollectionAdapter.VIEW_TYPE_LINK_PREVIEW => LinkPreviewViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_link, parent, false).asInstanceOf[LinearLayout], linkListener)
      case CollectionAdapter.VIEW_TYPE_SIMPLE_LINK => SimpleLinkViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_simple_link, parent, false).asInstanceOf[LinearLayout], linkListener)
      case _ => returning(null.asInstanceOf[ViewHolder])(_ => error(s"Unexpected ViewType: $viewType"))
    }

  def isFullSpan(position: Int): Boolean = {
    getItemViewType(position) match {
      case CollectionAdapter.VIEW_TYPE_FILE => true
      case CollectionAdapter.VIEW_TYPE_IMAGE => false
      case CollectionAdapter.VIEW_TYPE_LINK_PREVIEW => true
      case CollectionAdapter.VIEW_TYPE_SIMPLE_LINK => true
    }
  }

  def getItem(position: Int): MessageData = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => all.currentValue.getOrElse(Seq.empty)(position)
      case CollectionAdapter.VIEW_MODE_IMAGES => images.currentValue.getOrElse(Seq.empty)(position)
      case CollectionAdapter.VIEW_MODE_FILES => files.currentValue.getOrElse(Seq.empty)(position)
      case CollectionAdapter.VIEW_MODE_LINKS => links.currentValue.getOrElse(Seq.empty)(position)
    }
  }

  def getHeaderId(position: Int): Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => {
        all.currentValue.getOrElse(Seq.empty)(position).msgType match {
          case Message.Type.ANY_ASSET => Header.mainFiles
          case Message.Type.ASSET => Header.mainImages
          case Message.Type.RICH_MEDIA => Header.mainLinks
          case _ => Header.mainImages
        }
      }
      case _ => {
        val time = getItem(position).time
        val now = LocalDateTime.ofInstant(Instant.now(), ZoneId.systemDefault()).toLocalDate

        // TODO just testing headers here...
        if (now == LocalDateTime.ofInstant(time, ZoneId.systemDefault()).toLocalDate())
          Header.subToday
        else if (now.minus(1, ChronoUnit.DAYS) == LocalDateTime.ofInstant(time, ZoneId.systemDefault()).toLocalDate())
          Header.subYesterday
        else
          Header.subAgesAgo
      }
    }
  }

  def getHeaderView(parent: RecyclerView, position: Int): View = {
    if (header == null) {
      header = new CollectionHeaderLinearLayout(parent.getContext)
    }
    if (header.getLayoutParams == null) {
      header.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    header.nameView.setText(getHeaderText(getHeaderId(position)))

    val widthSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getWidth, View.MeasureSpec.UNSPECIFIED)
    val heightSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getHeight, View.MeasureSpec.EXACTLY)
    val childWidth: Int = ViewGroup.getChildMeasureSpec(widthSpec, parent.getPaddingLeft + parent.getPaddingRight, header.getLayoutParams.width)
    val childHeight: Int = ViewGroup.getChildMeasureSpec(heightSpec, parent.getPaddingTop + parent.getPaddingBottom, header.getLayoutParams.height)
    header.measure(childWidth, childHeight)
    header.layout(0, 0, parent.getMeasuredWidth, header.getMeasuredHeight)
    header
  }

  private def getHeaderText(headerId: Int): String = {
    headerId match {
      case Header.`mainImages` => "PICTURES"
      case Header.`mainFiles` => "FILES"
      case Header.`mainLinks` => "LINKS"
      case Header.`subToday` => "TODAY"
      case Header.`subYesterday` => "YESTERDAY"
      case Header.`subAgesAgo` => "AGES AGO"
      case _ => "Whatever"
    }
  }

  def getItemPosition(messageData: MessageData): Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => all.currentValue.map(_.indexOf(messageData)).getOrElse(-1)
      case CollectionAdapter.VIEW_MODE_FILES => files.currentValue.map(_.indexOf(messageData)).getOrElse(-1)
      case CollectionAdapter.VIEW_MODE_IMAGES => images.currentValue.map(_.indexOf(messageData)).getOrElse(-1)
      case CollectionAdapter.VIEW_MODE_LINKS => links.currentValue.map(_.indexOf(messageData)).getOrElse(-1)
      case _ => -1
    }
  }

  def getPreviousItem(messageData: MessageData): Option[MessageData] = {
    getItemPosition(messageData) match {
      case 0 => None
      case pos => Some(getItem(pos - 1))
    }
  }

  def getNextItem(messageData: MessageData): Option[MessageData] = {
    getItemPosition(messageData) match {
      case pos if pos >= getItemCount - 1 => None
      case pos => Some(getItem(pos + 1))
    }
  }

}

object Header {
  val mainImages: Int = 0
  val mainFiles: Int = 1
  val mainLinks: Int = 2
  val subToday: Int = 3
  val subYesterday: Int = 4
  val subAgesAgo: Int = 5
}

object CollectionAdapter {

  val VIEW_MODE_ALL: Int = 0
  val VIEW_MODE_IMAGES: Int = 1
  val VIEW_MODE_FILES: Int = 2
  val VIEW_MODE_LINKS: Int = 3

  val VIEW_TYPE_IMAGE = 0
  val VIEW_TYPE_FILE = 1
  val VIEW_TYPE_LINK_PREVIEW = 2
  val VIEW_TYPE_SIMPLE_LINK = 3

  case class CollectionHeaderLinearLayout(context: Context, attrs: AttributeSet, defStyleAttr: Int) extends LinearLayout(context, attrs, defStyleAttr) {

    def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)

    def this(context: Context) =  this(context, null)

    lazy val nameView: TextView = ViewUtils.getView(this, R.id.ttv__collection_header__name)

    LayoutInflater.from(context).inflate(R.layout.row_collection_header, this, true)
    setOrientation(LinearLayout.HORIZONTAL)
    setBackgroundColor(ContextCompat.getColor(context, R.color.header_background))
  }

  case class FileViewHolder(view: LinearLayout, listener: OnClickListener)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view) {
    val actionButton: View = ViewUtils.getView(view, R.id.v__row_collection__file_icon)
    val nameTextView: TextView = ViewUtils.getView(view, R.id.ttv__row_collection__file__filename)
    val detailTextView: TextView = ViewUtils.getView(view, R.id.ttv__row_collection__file__fileinfo)
    view.setOnClickListener(listener)

    def setAsset(asset: Signal[AssetData]) = asset.on(Threading.Ui) { a =>
      actionButton.setBackground(new FileDrawable(view.getContext, a.fileExtension))
      a.name.foreach(nameTextView.setText)
      detailTextView.setText(view.getContext.getString(R.string.content__file__status__default__size_and_extension,
        Formatter.formatFileSize(view.getContext, a.sizeInBytes), a.fileExtension.toUpperCase(Locale.getDefault)))
    }
  }

  case class CollViewHolder(view: AspectRatioImageView, listener: OnClickListener)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view) {
    view.setOnClickListener(listener)

    def setAssetMessage(messageData: MessageData, asset: Signal[AssetData], bitmap: (AssetId, Int) => Signal[Option[Bitmap]], width: Int, color: Int) = {
      view.setTag(messageData)
      view.setAspectRatio(1)
      view.setImageBitmap(null)
      view.setBackgroundColor(color)
      ViewUtils.setWidth(view, width)
      ViewUtils.setHeight(view, width)
      view.setAlpha(0f)
      view.animate
        .alpha(1f)
        .setDuration(view.getContext.getResources.getInteger(R.integer.content__image__directly_final_duration))
        .start()
      asset.flatMap(a => bitmap(a.id, width)).on(Threading.Ui) {
        case Some(b) => view.setImageBitmap(b)
        case None => //TODO bitmap didn't load
      }
    }
  }

  case class LinkPreviewViewHolder(view: LinearLayout, listener: OnClickListener)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view) {
    val titleTextView: TextView = ViewUtils.getView(view, R.id.ttv__row_conversation__link_preview__title)
    val urlTextView: TextView = ViewUtils.getView(view, R.id.ttv__row_conversation__link_preview__url)
    val previewImageAssetView: ImageAssetView = ViewUtils.getView(view, R.id.iv__row_conversation__link_preview__image)
    val previewImageContainer: FrameLayout = ViewUtils.getView(view, R.id.fl__row_conversation__link_preview__image_container)
    view.setOnClickListener(listener)

    private val imageAssetModelObserver: ModelObserver[ImageAsset] = new ModelObserver[ImageAsset]() {
      override def updated(imageAsset: ImageAsset): Unit = {
        previewImageAssetView.setImageAsset(imageAsset)
      }
    }

    def setMessageData(messageData: MessageData): Unit = {
      view.setTag(messageData)
      titleTextView.setText(messageData.content.find(_.openGraph.nonEmpty).map(_.openGraph.get.title).getOrElse(""))
      urlTextView.setText(messageData.content.find(_.tpe == Message.Part.Type.WEB_LINK).map(_.content).getOrElse(""))
      val imageAsset = ImageAssetFactory.getImageAsset(messageData.content.find(_.openGraph.nonEmpty).flatMap(_.openGraph.flatMap(_.image)).getOrElse(Uri.EMPTY))
      imageAssetModelObserver.addAndUpdate(imageAsset)
    }
  }

  case class SimpleLinkViewHolder(view: LinearLayout, listener: OnClickListener)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view) {
    val urlTextView: TextView = ViewUtils.getView(view, R.id.ttv__row_collection__link__url)
    view.setOnClickListener(listener)

    def setMessageData(messageData: MessageData): Unit = {
      view.setTag(messageData)
      urlTextView.setText(messageData.content.find(content => content.tpe == Message.Part.Type.TEXT && Patterns.WEB_URL.matcher(content.content).matches()).map(_.content).getOrElse(""))
    }
  }
}
