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

import android.os.Bundle
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.View.{OnClickListener, OnLayoutChangeListener, OnTouchListener}
import android.view._
import com.waz.model.{AssetData, AssetId}
import com.waz.service.ZMessaging
import com.waz.service.assets.AssetService.BitmapResult
import com.waz.service.assets.AssetService.BitmapResult.BitmapLoaded
import com.waz.service.images.BitmapSignal
import com.waz.threading.Threading
import com.waz.ui.MemoryImageCache.BitmapRequest.Single
import com.waz.utils.events.Signal
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.images.TouchImageView
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R}

class SingleImageCollectionFragment extends BaseFragment[CollectionFragment.Container] with FragmentHelper with OnBackPressedListener {

  lazy val collectionController = getControllerFactory.getCollectionsController

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val assetsStorage = zms.map(_.assetsStorage)

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_single_image_collections, container, false)
    val shareButton: GlyphTextView = ViewUtils.getView(view, R.id.gtv__share_button)
    val imageView: TouchImageView = ViewUtils.getView(view, R.id.tiv__image_view)
    shareButton.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {
        collectionController.focusedItem.currentValue match {
          case Some(Some(messageData)) => collectionController.shareMessageData(messageData)
          case _ =>
        }
      }
    })

    val gestureDetector = new GestureDetector(getContext, new SimpleOnGestureListener(){
      override def onFling(e1: MotionEvent, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean = {
        if (imageView.isZoomed) return true
        if (velocityX > SingleImageCollectionFragment.MIN_FLING_THRESHOLD) {
          collectionController.requestPreviousItem()
        } else if (velocityX < -SingleImageCollectionFragment.MIN_FLING_THRESHOLD) {
          collectionController.requestNextItem()
        }
        true
      }
    })

    imageView.setOnTouchListener(new OnTouchListener {
      override def onTouch(v: View, event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)
    })

    view
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)

    lazy val onLayoutChangeListener: OnLayoutChangeListener = new OnLayoutChangeListener {
      override def onLayoutChange(v: View, left: Int, top: Int, right: Int, bottom: Int, oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int): Unit = {
        val assetId = AssetId(getArguments.getString(SingleImageCollectionFragment.ARG_ASSET_ID))
        setAsset(assetId)
        view.removeOnLayoutChangeListener(onLayoutChangeListener)
      }
    }
    view.addOnLayoutChangeListener(onLayoutChangeListener)
  }

  override def onBackPressed(): Boolean = true


  private def loadBitmap(assetId: AssetId, width: Int)  = zms.flatMap { zms =>
    zms.assetsStorage.signal(assetId).flatMap {
      case data@AssetData.IsImage() => BitmapSignal(data, Single(width), zms.imageLoader, zms.imageCache)
      case _ => Signal.empty[BitmapResult]
    }.map{
      case BitmapLoaded(bmp, _) => Option(bmp)
      case _ => None
    }
  }

  def setAsset(assetId: AssetId): Unit = {
    val assetSignal = assetsStorage.flatMap(_.signal(assetId))
    val imageView: TouchImageView = ViewUtils.getView(getView, R.id.tiv__image_view)
    imageView.setImageBitmap(null)
    imageView.setAlpha(0f)
    assetSignal.flatMap(a => loadBitmap(a.id, getView.getWidth)).on(Threading.Ui) {
      case Some(b) =>
        imageView.setImageBitmap(b)
        imageView.animate
          .alpha(1f)
          .setDuration(getResources.getInteger(R.integer.content__image__directly_final_duration))
          .start()
      case None =>
    }
  }
}

object SingleImageCollectionFragment {

  val TAG = SingleImageCollectionFragment.getClass.getSimpleName

  val ARG_ASSET_ID = "ARG_ASSET_ID"

  private val MIN_FLING_THRESHOLD = 1000

  def newInstance(assetId: AssetId): SingleImageCollectionFragment = {
    val fragment = new SingleImageCollectionFragment
    val bundle = new Bundle()
    bundle.putString(ARG_ASSET_ID, assetId.str)
    fragment.setArguments(bundle)
    fragment
  }
}
