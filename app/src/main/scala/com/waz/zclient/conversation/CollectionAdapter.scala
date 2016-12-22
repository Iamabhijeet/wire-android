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
import android.support.v4.content.ContextCompat
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.ViewHolder
import android.text.format.Formatter
import android.util.TypedValue
import android.view.View.OnClickListener
import android.view.ViewGroup.LayoutParams
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{LinearLayout, TextView}
import com.waz.ZLog._
import com.waz.api.Message
import com.waz.model.{AssetData, AssetId, MessageData}
import com.waz.threading.Threading
import com.waz.utils.events.{EventContext, Signal}
import com.waz.utils.returning
import com.waz.zclient.conversation.CollectionAdapter.{CollViewHolder, FileViewHolder}
import com.waz.zclient.pages.main.conversation.views.AspectRatioImageView
import com.waz.zclient.ui.drawable.FileDrawable
import com.waz.zclient.ui.text.TypefaceTextView
import com.waz.zclient.ui.utils.ResourceUtils
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.{Injectable, Injector, R}
import org.threeten.bp.temporal.ChronoUnit
import org.threeten.bp.{Instant, LocalDateTime, ZoneId}

//For now just handling images
class CollectionAdapter(screenWidth: Int, columns: Int, ctrler: CollectionController)(implicit context: Context, injector: Injector, eventContext: EventContext) extends RecyclerView.Adapter[ViewHolder] with Injectable {

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

  all.onChanged.on(Threading.Ui) { _ =>
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => notifyDataSetChanged()
      case _ =>
    }
  }

  override def getItemCount: Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => all.currentValue.map(_.size).getOrElse(0)
      case CollectionAdapter.VIEW_MODE_FILES => files.currentValue.map(_.size).getOrElse(0)
      case CollectionAdapter.VIEW_MODE_IMAGES => images.currentValue.map(_.size).getOrElse(0)
      case _ => 0
    }
  }

  override def getItemViewType(position: Int): Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => {
        all.currentValue.getOrElse(Seq.empty)(position).msgType match {
          case Message.Type.ANY_ASSET => CollectionAdapter.VIEW_TYPE_FILE
          case Message.Type.ASSET => CollectionAdapter.VIEW_TYPE_IMAGE
        }
      }
      case CollectionAdapter.VIEW_MODE_FILES => CollectionAdapter.VIEW_TYPE_FILE
      case CollectionAdapter.VIEW_MODE_IMAGES => CollectionAdapter.VIEW_TYPE_IMAGE
      case _ => 0
    }
  }

  override def onBindViewHolder(holder: ViewHolder, position: Int): Unit = {
    holder match {
      case f: FileViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _files, position).foreach(f.setAsset)
      case c: CollViewHolder => assetSignal(if (contentMode == CollectionAdapter.VIEW_MODE_ALL) _all else _images, position).foreach(s => c.setAsset(s, ctrler.bitmapSignal, screenWidth / columns, ResourceUtils.getRandomAccentColor(context)))
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

  private def assetSignal(col: Seq[MessageData], pos: Int): Option[Signal[AssetData]] = col.lift(pos).map(m => ctrler.assetSignal(m.assetId))

  val imageListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      // TODO
    }
  }

  val fileListener = new OnClickListener {
    override def onClick(v: View): Unit = {
      // TODO
    }
  }

  override def onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
    viewType match {
      case CollectionAdapter.VIEW_TYPE_FILE => FileViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_file, parent, false).asInstanceOf[LinearLayout], fileListener)
      case CollectionAdapter.VIEW_TYPE_IMAGE => CollViewHolder(LayoutInflater.from(parent.getContext).inflate(R.layout.row_collection_image, parent, false).asInstanceOf[AspectRatioImageView], imageListener)
      case _ => returning(null.asInstanceOf[ViewHolder])(_ => error(s"Unexpected ViewType: $viewType"))
    }

  def isFullSpan(position: Int): Boolean = {
    getItemViewType(position) match {
      case CollectionAdapter.VIEW_TYPE_FILE => true
      case CollectionAdapter.VIEW_TYPE_IMAGE => false
    }
  }

  def getItem(position: Int): MessageData = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => all.currentValue.getOrElse(Seq.empty)(position)
      case CollectionAdapter.VIEW_MODE_IMAGES => images.currentValue.getOrElse(Seq.empty)(position)
      case CollectionAdapter.VIEW_MODE_FILES => files.currentValue.getOrElse(Seq.empty)(position)
    }
  }

  def getHeaderId(position: Int): Int = {
    contentMode match {
      case CollectionAdapter.VIEW_MODE_ALL => {
        all.currentValue.getOrElse(Seq.empty)(position).msgType match {
          case Message.Type.ANY_ASSET => Header.mainFiles
          case Message.Type.ASSET => Header.mainImages
          case Message.Type.RICH_MEDIA => Header.mainLinks
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
    val header = new LinearLayout(parent.getContext)
    if (header.getLayoutParams == null) {
      header.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    header.setBackgroundColor(ContextCompat.getColor(parent.getContext, R.color.light_graphite_24))

    val textView = new TypefaceTextView(parent.getContext)
    textView.setText(getHeaderText(getHeaderId(position)))
    textView.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources.getDimensionPixelSize(R.dimen.wire__text_size__small))
    textView.setTypeface(context.getString(R.string.wire__typeface__bold))
    val padding = parent.getContext.getResources.getDimensionPixelSize(R.dimen.wire__padding__small)
    textView.setPadding(padding, padding, padding, padding)
    header.addView(textView)

    val heightSpec: Int = View.MeasureSpec.makeMeasureSpec(parent.getHeight, View.MeasureSpec.EXACTLY)
    val childHeight: Int = ViewGroup.getChildMeasureSpec(heightSpec, parent.getPaddingTop + parent.getPaddingBottom, header.getLayoutParams.height)
    header.measure(LayoutParams.MATCH_PARENT, childHeight)
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

    def setAsset(asset: Signal[AssetData], bitmap: (AssetId, Int) => Signal[Option[Bitmap]], width: Int, color: Int) = asset.on(Threading.Ui) { a =>
      view.setAspectRatio(1)
      view.setImageBitmap(null)
      view.setBackgroundColor(color)
      ViewUtils.setWidth(view, width)
      ViewUtils.setHeight(view, width)
      bitmap(a.id, view.getWidth).on(Threading.Ui) {
        case Some(b) => view.setImageBitmap(b)
        case None => //TODO bitmap didn't load
      }

    }
  }

}
