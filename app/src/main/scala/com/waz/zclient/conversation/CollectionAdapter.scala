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
import android.graphics.Bitmap
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.{AdapterDataObserver, ViewHolder}
import android.util.AttributeSet
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{LinearLayout, TextView}
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.bitmap.BitmapUtils
import com.waz.model._
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.assets.AssetService.BitmapResult.BitmapLoaded
import com.waz.service.images.BitmapSignal
import com.waz.threading.Threading
import com.waz.ui.MemoryImageCache.BitmapRequest.Single
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.returning
import com.waz.zclient.conversation.CollectionAdapter._
import com.waz.zclient.pages.main.conversation.views.AspectRatioImageView
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.ui.utils.ResourceUtils
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.{CollectionItemView, FileViewHolder, LinkPreviewViewHolder, SimpleLinkViewHolder}
import com.waz.zclient.{Injectable, Injector, R, ViewHelper}
import org.threeten.bp._
import org.threeten.bp.temporal.ChronoUnit

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
      case f: FileViewHolder => messageDataForPostion(position, _files).foreach(md => f.setMessageData(md))
      case c: CollectionImageViewHolder => messageDataForPostion(position, _images).foreach(md => c.setMessageData(md, screenWidth / columns, ResourceUtils.getRandomAccentColor(context)))
      case l: LinkPreviewViewHolder => messageDataForPostion(position, _links).foreach(md => l.setMessageData(md))
      case l: SimpleLinkViewHolder => messageDataForPostion(position, _links).foreach(md => l.setMessageData(md))
    }
  }

  private def messageDataForPostion(pos: Int, seq: Seq[MessageData]): Option[MessageData] = {
    (if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else seq).lift(pos)
  }

  def onBackPressed(): Boolean = contentMode match {
    case CollectionAdapter.VIEW_MODE_ALL => false
    case _ =>
      contentMode = CollectionAdapter.VIEW_MODE_ALL
      notifyDataSetChanged()
      true
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

  val imageListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      v.getTag match {
        case md: MessageData => ctrler.focusedItem ! Some(md)
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
        case md: MessageData => ctrler.focusedItem ! Some(md)
        case _ =>
      }
    }
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    viewType match {
      case CollectionAdapter.VIEW_TYPE_FILE => FileViewHolder(inflateCollectionItemView(R.layout.collection_file_asset, parent))
      case CollectionAdapter.VIEW_TYPE_IMAGE => CollectionImageViewHolder(inflateCollectionImageView(parent), imageListener)
      case CollectionAdapter.VIEW_TYPE_LINK_PREVIEW => LinkPreviewViewHolder(inflateCollectionItemView(R.layout.collection_link_preview, parent))
      case CollectionAdapter.VIEW_TYPE_SIMPLE_LINK => SimpleLinkViewHolder(inflateCollectionItemView(R.layout.collection_text, parent))
      case _ => returning(null.asInstanceOf[ViewHolder])(_ => error(s"Unexpected ViewType: $viewType"))
    }

  private def inflateCollectionImageView(parent: ViewGroup): CollectionImageView = {
    val view = new CollectionImageView(context)
    parent.addView(view)
    view
  }

  private def inflateCollectionItemView(contentId: Int, parent: ViewGroup): CollectionItemView = {
    val view = new CollectionItemView(context)
    view.inflateContent(contentId)
    parent.addView(view)
    view
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

  def getHeaderId(position: Int): HeaderId = {
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
        val messageDate = LocalDateTime.ofInstant(time, ZoneId.systemDefault()).toLocalDate()

        if (now == messageDate)
          Header.subToday
        else if (now.minus(1, ChronoUnit.DAYS) == messageDate)
          Header.subYesterday
        else
          HeaderId(HeaderType.MonthName, messageDate.getMonthValue, messageDate.getYear)

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
    header.countView.setText(getHeaderCountText(getHeaderId(position)))
    if (contentMode == CollectionAdapter.VIEW_MODE_ALL) {
      header.arrowView.setVisibility(View.VISIBLE)
    } else {
      header.arrowView.setVisibility(View.GONE)
    }

    val widthSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getWidth, View.MeasureSpec.EXACTLY)
    val heightSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getHeight, View.MeasureSpec.EXACTLY)
    val childWidth: Int = ViewGroup.getChildMeasureSpec(widthSpec, parent.getPaddingLeft + parent.getPaddingRight, header.getLayoutParams.width)
    val childHeight: Int = ViewGroup.getChildMeasureSpec(heightSpec, parent.getPaddingTop + parent.getPaddingBottom, header.getLayoutParams.height)
    header.measure(childWidth, childHeight)
    header.layout(0, 0, header.getMeasuredWidth, header.getMeasuredHeight)
    header
  }

  private def getHeaderText(headerId: HeaderId): String = {
    headerId match {
      case HeaderId(HeaderType.Images, _, _) => "PICTURES"
      case HeaderId(HeaderType.Files, _, _) => "FILES"
      case HeaderId(HeaderType.Links, _, _) => "LINKS"
      case HeaderId(HeaderType.Today, _, _) => "TODAY"
      case HeaderId(HeaderType.Yesterday, _, _) => "YESTERDAY"
      case HeaderId(HeaderType.MonthName, m, y) => Month.of(m).toString + " " + y
      case _ => ""
    }
  }

  private def getHeaderCountText(headerId: HeaderId): String = {
    headerId match {
      case HeaderId(HeaderType.Images, _, _) => "All " + images.currentValue.getOrElse(Seq()).length
      case HeaderId(HeaderType.Files, _, _) => "All " + files.currentValue.getOrElse(Seq()).length
      case HeaderId(HeaderType.Links, _, _) => "All " + links.currentValue.getOrElse(Seq()).length
      case _ => ""
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

case class HeaderId(headerType:Int, month: Int = 0, year: Int = 0)

object HeaderType {
  val Images: Int = 0
  val Files: Int = 1
  val Links: Int = 2
  val Today: Int = 3
  val Yesterday: Int = 4
  val MonthName: Int = 5
}

object Header {
  val invalid = HeaderId(-1)
  val mainImages = HeaderId(HeaderType.Images)
  val mainFiles = HeaderId(HeaderType.Files)
  val mainLinks = HeaderId(HeaderType.Links)
  val subToday = HeaderId(HeaderType.Today)
  val subYesterday = HeaderId(HeaderType.Yesterday)
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
    lazy val countView: TextView = ViewUtils.getView(this, R.id.ttv__collection_header__count)
    lazy val arrowView: GlyphTextView = ViewUtils.getView(this, R.id.gtv__arrow)

    LayoutInflater.from(context).inflate(R.layout.row_collection_header, this, true)
  }

  class CollectionImageView(context: Context) extends AspectRatioImageView(context) with ViewHelper {
    val zms = inject[Signal[ZMessaging]]
    val messageData = Signal[MessageData]()
    val width = Signal[Int]()

    messageData.zip(width).flatMap{
      case (msg, w) =>
        zms.flatMap { zms =>
          zms.assetsStorage.signal(msg.assetId).flatMap {
            case data@AssetData.IsImage() => BitmapSignal(data, Single(w), zms.imageLoader, zms.imageCache)
            case _ => Signal.empty[BitmapResult]
          }.map{
            case BitmapLoaded(bmp, _) => Option(BitmapUtils.cropRect(bmp, w))
            case _ => None
          }
        }
      case _ => Signal[Option[Bitmap]](None)
    }.on(Threading.Ui) {
      case Some(b) => setImageBitmap(b)
      case _ =>
    }

    def setMessageData(messageData: MessageData, width: Int, color: Int) = {
      setAspectRatio(1)
      ViewUtils.setWidth(this, width)
      ViewUtils.setHeight(this, width)
      if (this.messageData.currentValue.exists(_.assetId != messageData.assetId) || this.width.currentValue.exists(_ != width)) {
        setImageBitmap(null)
        setBackgroundColor(color)
        setAlpha(0f)
        animate
          .alpha(1f)
          .setDuration(context.getResources.getInteger(R.integer.content__image__directly_final_duration))
          .start()
      }
      this.width ! width
      this.messageData ! messageData
    }
  }

  case class CollectionImageViewHolder(view: CollectionImageView, listener: OnClickListener)(implicit eventContext: EventContext) extends RecyclerView.ViewHolder(view) {
    view.setOnClickListener(listener)

    def setMessageData(messageData: MessageData, width: Int, color: Int) = {
      view.setTag(messageData)
      view.setMessageData(messageData, width, color)
    }
  }
}
