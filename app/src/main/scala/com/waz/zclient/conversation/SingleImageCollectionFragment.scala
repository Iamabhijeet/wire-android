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

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View.OnClickListener
import android.view.{LayoutInflater, View, ViewGroup}
import android.widget.{ImageView, RelativeLayout}
import com.waz.model.{AssetData, AssetId}
import com.waz.threading.Threading
import com.waz.utils.events.Signal
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.{FragmentHelper, OnBackPressedListener, R, ViewHelper}
import com.waz.zclient.ui.text.GlyphTextView
import com.waz.zclient.utils.ViewUtils

class SingleImageCollectionFragment extends BaseFragment[CollectionFragment.Container] with FragmentHelper with OnBackPressedListener {

  lazy val collectionController = new CollectionController()

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_single_image_collections, container, false)
    val shareButton: GlyphTextView = ViewUtils.getView(view, R.id.gtv__share_button)
    val assetId = AssetId(getArguments.getString(SingleImageCollectionFragment.ARG_ASSET_ID))
    setAsset(assetId)
    shareButton.setOnClickListener(new OnClickListener {
      override def onClick(v: View): Unit = {}
    })
    view
  }

  override def onBackPressed(): Boolean = true

  def setAsset(assetId: AssetId): Unit = setAsset(collectionController.assetSignal(assetId), collectionController.bitmapSignal)

  private def setAsset(asset: Signal[AssetData], bitmap: (AssetId, Int) => Signal[Option[Bitmap]]): Unit = asset.on(Threading.Ui) { a =>
    val imageView: ImageView = ViewUtils.getView(getView, R.id.ariv__image_view)
    imageView.setImageBitmap(null)
    bitmap(a.id, getView.getWidth).on(Threading.Ui) {
      case Some(b) => imageView.setImageBitmap(b)
      case None =>
    }
  }
}

object SingleImageCollectionFragment {

  val TAG = SingleImageCollectionFragment.getClass.getSimpleName

  private val ARG_ASSET_ID = "ARG_ASSET_ID"

  def newInstance(assetId: AssetId): SingleImageCollectionFragment = {
    val fragment = new SingleImageCollectionFragment
    val bundle = new Bundle()
    bundle.putString(ARG_ASSET_ID, assetId.str)
    fragment.setArguments(bundle)
    fragment
  }
}
