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
package com.waz.zclient.calling.controllers

import _root_.com.waz.api.VoiceChannelState._
import _root_.com.waz.service.ZMessaging
import _root_.com.waz.utils.events.{EventContext, Signal}
import android.os.PowerManager
import com.waz.api.{IConversation, KindOfCall}
import com.waz.model.UserId
import com.waz.service.call.CallInfo._
import com.waz.threading.Threading
import com.waz.zclient.calling.CallingActivity
import com.waz.zclient.{Injectable, Injector, WireContext}

class GlobalCallingController(cxt: WireContext)(implicit inj: Injector) extends Injectable {

  private implicit val eventContext = cxt.eventContext

  val zmsOpt = inject[Signal[Option[ZMessaging]]]

  val zms = zmsOpt.collect { case Some(z) => z }
  val userStorage = zms map (_.usersStorage)

  private val screenManager = new ScreenManager

  val prefs = zms.map(_.prefs)

  val v3Service = zms.map(_.calling)
  val v3Call = v3Service.flatMap(_.currentCall)
  val isV3CallActive = v3Call.map { case IsActive() => true; case _ => false}
  val isV3Call = for {
    p <- prefs
    active <- isV3CallActive
  } yield active || p.callingV3

  val channels = zms.flatMap(_.voiceContent.ongoingAndTopIncomingChannel)
  val voiceService = zms.map(_.voice)

  val convId = isV3Call.flatMap {
    case true => v3Call.map(_.convId)
    case false => channels map {
      case (ongoing, incoming) => ongoing.orElse(incoming).map(_.id)
    }
  }.collect { case Some(c) => c }

  val voiceServiceAndCurrentConvId = for {
    vcs <- voiceService
    cId <- convId
  } yield (vcs, cId)

  //Note, we can't rely on the channels from ongoingAndTopIncoming directly, as they only update the presence of a channel, not their internal state
  val currentChannel = voiceServiceAndCurrentConvId.flatMap { case (vcs, id) => vcs.voiceChannelSignal(id) }
  val v2CallState = currentChannel.map(_.state)

  val callState = isV3Call.flatMap {
    case true => v3Call.map(_.state)
    case _ => v2CallState
  }

  val conversation = zms.zip(convId) flatMap { case (zms, convId) => zms.convsStorage.signal(convId) }
  val conversationName = conversation map (data => if (data.convType == IConversation.Type.GROUP) data.name.filter(!_.isEmpty).getOrElse(data.generatedName) else data.generatedName)

  val selfUser = zms flatMap (_.users.selfUser)

  val callerId = isV3Call.flatMap {
    case true => v3Call flatMap { case info =>
      (info.others, info.state) match {
        case (_, SELF_CALLING) => selfUser.map(_.id)
        case (others, OTHER_CALLING) if others.size == 1 => Signal.const(others.head)
        case _ => Signal.empty[UserId] //TODO Dean do I need this information for other call states?
      }
    }
    case _ => currentChannel map (_.caller) flatMap (_.fold(Signal.empty[UserId])(Signal(_)))
  }
  val callerData = userStorage.zip(callerId).flatMap { case (storage, id) => storage.signal(id) }

  val groupCall = isV3Call.flatMap {
    case true => Signal.const(false)
    case _ => currentChannel map (_.tracking.kindOfCall == KindOfCall.GROUP)
  }

  val videoCall = isV3Call.flatMap {
    case true => v3Call.map(_.withVideo)
    case _ => currentChannel.map(_.video.isVideoCall)
  }

  //Here we need to respond to the case where Zms could become None mid call (e.g. force logout?), in which case we should drop the call
  val activeCall = zmsOpt.flatMap {
    case Some(_) => isV3Call.flatMap {
      case true => isV3CallActive
      case _ => v2CallState.map {
        case SELF_CALLING | SELF_JOINING | SELF_CONNECTED | OTHER_CALLING | OTHERS_CONNECTED => true
        case _ => false
      }
      case _ => Signal.const(false)
    }
  }

  private var _wasUiActiveOnCallStart = false

  def wasUiActiveOnCallStart = _wasUiActiveOnCallStart

  val onCallStarted = activeCall.onChanged.filter(_ == true).map { _ =>
    val active = zmsOpt.flatMap(_.fold(Signal.const(false))(_.lifecycle.uiActive)).currentValue.getOrElse(false)
    _wasUiActiveOnCallStart = active
    active
  }

  onCallStarted.on(Threading.Ui) { _ =>
    CallingActivity.start(cxt)
  }(EventContext.Global)

  activeCall.onChanged.filter(_ == false).on(Threading.Ui) { _ =>
    screenManager.releaseWakeLock()
  }(EventContext.Global)

  (for {
    v <- videoCall
    st <- callState
  } yield (v, st)) {
    case (true, _) => screenManager.setStayAwake()
    case (false, OTHER_CALLING) => screenManager.setStayAwake()
    case (false, SELF_CALLING | SELF_JOINING | SELF_CONNECTED) => screenManager.setProximitySensorEnabled()
    case _ => screenManager.releaseWakeLock()
  }
}

private class ScreenManager(implicit injector: Injector) extends Injectable {

  private val TAG = "CALLING_WAKE_LOCK"

  private val powerManager = Option(inject[PowerManager])

  private var stayAwake = false
  private var wakeLock: Option[PowerManager#WakeLock] = None

  def setStayAwake() = {
    (stayAwake, wakeLock) match {
      case (_, None) | (false, Some(_)) =>
        this.stayAwake = true
        createWakeLock();
      case _ => //already set
    }
  }

  def setProximitySensorEnabled() = {
    (stayAwake, wakeLock) match {
      case (_, None) | (true, Some(_)) =>
        this.stayAwake = false
        createWakeLock();
      case _ => //already set
    }
  }

  private def createWakeLock() = {
    val flags = if (stayAwake)
      PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP
    else PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK
    releaseWakeLock()
    wakeLock = powerManager.map(_.newWakeLock(flags, TAG))
    wakeLock.foreach(_.acquire())
  }

  def releaseWakeLock() = {
    for (wl <- wakeLock if wl.isHeld) wl.release()
    wakeLock = None
  }
}

