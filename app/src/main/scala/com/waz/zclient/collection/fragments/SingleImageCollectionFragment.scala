/**
 * Wire
 * Copyright (C) 2018 Wire Swiss GmbH
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
package com.waz.zclient.collection.fragments

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View.OnLongClickListener
import android.view.ViewGroup.LayoutParams
import android.view._
import androidx.viewpager.widget.{PagerAdapter, ViewPager}
import androidx.viewpager.widget.ViewPager.OnPageChangeListener
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.waz.api.MessageFilter
import com.waz.log.BasicLogging.LogTag.DerivedLogTag
import com.waz.model.{AssetId, MessageData}
import com.waz.service.ZMessaging
import com.waz.threading.Threading
import com.waz.utils.events._
import com.waz.zclient._
import com.waz.zclient.collection.controllers.CollectionController
import com.waz.zclient.collection.controllers.CollectionController.{AllContent, ContentType, Images}
import com.waz.zclient.collection.fragments.SingleImageCollectionFragment.ImageSwipeAdapter
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.glide.WireGlide
import com.waz.zclient.log.LogUI._
import com.waz.zclient.messages.RecyclerCursor
import com.waz.zclient.messages.RecyclerCursor.RecyclerNotifier
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.pages.BaseFragment
import com.waz.zclient.pages.main.conversationpager.CustomPagerTransformer
import com.waz.zclient.utils.ViewUtils
import com.waz.zclient.views.images.TouchImageView

import scala.collection.mutable
import scala.concurrent.Future

class SingleImageCollectionFragment
  extends BaseFragment[CollectionFragment.Container]
    with FragmentHelper
    with OnBackPressedListener {

  import Threading.Implicits.Ui

  lazy val zms = inject[Signal[ZMessaging]]
  lazy val messageActions = inject[MessageActionsController]
  lazy val collectionController = inject[CollectionController]

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val view = inflater.inflate(R.layout.fragment_single_image_collections, container, false)
    val pager: ViewPager = ViewUtils.getView(view, R.id.image_view_pager)
    val imageSwipeAdapter = new ImageSwipeAdapter(getContext)
    pager.setAdapter(imageSwipeAdapter)

    pager.addOnPageChangeListener(new OnPageChangeListener {
      override def onPageScrollStateChanged(state: Int): Unit = {}
      override def onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int): Unit = {}
      override def onPageSelected(position: Int): Unit = {
        collectionController.focusedItem ! imageSwipeAdapter.getItem(position)
      }
    })
    pager.setPageTransformer(false, new CustomPagerTransformer(CustomPagerTransformer.SLIDE))

    getFocusedItem(imageSwipeAdapter) foreach { pos =>
      if (pos >= 0) pager.setCurrentItem(pos, false)
    }

    view
  }

  private def getFocusedItem(adapter: ImageSwipeAdapter) = collectionController.focusedItem.head flatMap {
    case Some(msg) => adapter.cursor.head flatMap { c => c.positionForMessage(msg) }
    case None => Future.successful(-1)
  }

  override def onDestroyView(): Unit = {
    val viewPager: ViewPager = ViewUtils.getView(getView, R.id.image_view_pager)
    viewPager.getAdapter.asInstanceOf[ImageSwipeAdapter].recyclerCursor.foreach(_.close())
    viewPager.setAdapter(null)
    super.onDestroyView()
  }

  override def onBackPressed(): Boolean = true
}

object SingleImageCollectionFragment {

  val TAG = SingleImageCollectionFragment.getClass.getSimpleName

  def newInstance(): SingleImageCollectionFragment = new SingleImageCollectionFragment

  class ImageSwipeAdapter(context: Context)(implicit injector: Injector, ev: EventContext) extends PagerAdapter with Injectable{ self =>

    private val discardedImages = mutable.Queue[SwipeImageView]()

    private val zms = inject[Signal[ZMessaging]]

    val contentMode = Signal[ContentType](AllContent)

    val notifier = new RecyclerNotifier(){
      override def notifyDataSetChanged(): Unit = self.notifyDataSetChanged()

      override def notifyItemRangeInserted(index: Int, length: Int): Unit = self.notifyDataSetChanged()

      override def notifyItemRangeChanged(index: Int, length: Int): Unit = self.notifyDataSetChanged()

      override def notifyItemRangeRemoved(pos: Int, count: Int): Unit = self.notifyDataSetChanged()
    }

    var recyclerCursor: Option[RecyclerCursor] = None
    val cursor = for {
      zs <- zms
      convId <- inject[ConversationController].currentConvId
    } yield new RecyclerCursor(convId, zs, notifier, Some(MessageFilter(Some(Images.typeFilter))))

    cursor.on(Threading.Ui) { c =>
      if (!recyclerCursor.contains(c)) {
        recyclerCursor.foreach(_.close())
        recyclerCursor = Some(c)
        notifier.notifyDataSetChanged()
      }
    }

    def getItem(position: Int): Option[MessageData] = {
      recyclerCursor.flatMap{
        case c if c.count > position => Some(c.apply(position).message)
        case _ => None
      }
    }

    override def instantiateItem(container: ViewGroup, position: Int): AnyRef = {
      val imageView = if (discardedImages.nonEmpty) discardedImages.dequeue() else new SwipeImageView(context)
      imageView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
      imageView.setImageDrawable(new ColorDrawable(Color.TRANSPARENT))
      imageView.setPosition(position)
      container.addView(imageView)
      getItem(position).foreach(imageView.setMessageData)
      imageView
    }

    override def destroyItem(container: ViewGroup, position: Int, obj: scala.Any): Unit = {
      val view = obj.asInstanceOf[SwipeImageView]
      view.resetZoom()
      discardedImages.enqueue(view)
      container.removeView(view)
    }

    override def isViewFromObject(view: View, obj: scala.Any): Boolean =
      (view, obj) match {
        case (v: SwipeImageView, o: SwipeImageView) => v.getPosition == o.getPosition
        case _ => false
      }

    override def getCount: Int = recyclerCursor.fold(0)(_.count)
  }

  class SwipeImageView(context: Context, attrs: AttributeSet, style: Int)
                      (implicit injector: Injector, ev: EventContext)
    extends TouchImageView(context, attrs, style)
      with Injectable
      with DerivedLogTag {

    def this(context: Context, attrs: AttributeSet)(implicit injector: Injector, ev: EventContext) = this(context, attrs, 0)
    def this(context: Context)(implicit injector: Injector, ev: EventContext) = this(context, null, 0)

    private var position: Int = 0

    lazy val zms = inject[Signal[ZMessaging]]
    lazy val messageActions = inject[MessageActionsController]

    private var messageData = Option.empty[MessageData]

    setOnLongClickListener(new OnLongClickListener {
      override def onLongClick(v: View): Boolean = {
        messageData.foreach { md =>
          import Threading.Implicits.Ui
          zms.head.flatMap(_.msgAndLikes.combineWithLikes(md)).map {
            messageActions.showDialog(_, fromCollection = true)
          }
        }
        true
      }
    })

    def setAsset(assetId: AssetId): Unit = {
      verbose(l"$this Setting asset: $assetId")
      WireGlide(getContext)
        .load(assetId)
        .apply(new RequestOptions().fitCenter().placeholder(new ColorDrawable(Color.TRANSPARENT)))
        .transition(DrawableTransitionOptions.withCrossFade())
        .into(this)
    }

    def setMessageData(messageData: MessageData): Unit = {
      this.messageData = Option(messageData)
      verbose(l"Setting message data: $messageData")
      messageData.assetId match {
        case Some(id: AssetId) => setAsset(id)
        case _ =>
      }
    }

    def setPosition(position: Int): Unit = {
      this.position = position
    }

    def getPosition: Int = this.position

  }

}
