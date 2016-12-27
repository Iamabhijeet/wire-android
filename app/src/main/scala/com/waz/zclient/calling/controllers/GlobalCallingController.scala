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
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog.verbose
import com.waz.api.{IConversation, KindOfCall, VoiceChannelState}
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
  val isV3Call = v3Call.map {
    case IsActive() =>
      verbose("v3 call active")
      true
    case _ => false
  }

  /**
    * Opt Signals - these signals should be used where empty states (i.e, Nones) are important to the devices state. For example
    * if we have no zms instance, we should not have an active call. If we don't have a conversation, we shouldn't have an active
    * call. If there is no active call, for whatever reason, there should be no calling activity, and so on. The other signals
    * derived from these ones using `collect` will not propagate empty values, but that's okay since the calling UI should be
    * torn down before they can be of use to us anyway.
    */

  val convIdOpt = isV3Call.flatMap {
    case true => v3Call.map(_.convId)
    case false => zms.flatMap(_.voiceContent.ongoingAndTopIncomingChannel).map {
      case (ongoing, incoming) => ongoing.orElse(incoming).map(_.id)
    }
  }

  val callStateOpt: Signal[Option[VoiceChannelState]] = zmsOpt.flatMap {
    case Some(zms) => convIdOpt.flatMap {
      case Some(cId) => isV3Call.flatMap {
        case true => v3Call.map(i => Some(i.state))
        case _ => zms.voice.voiceChannelSignal(cId).map(d => Some(d.state))
      }
      case None => Signal.const(None)
    }
    case _ => Signal.const(None)
  }

  val activeCall = zmsOpt.flatMap {
    case Some(zms) => isV3Call.flatMap {
      case true => Signal.const(true)
      case _ => callStateOpt.map {
        case Some(SELF_CALLING | SELF_JOINING | SELF_CONNECTED | OTHER_CALLING | OTHERS_CONNECTED) => true
        case _ => false
      }
    }
    case _ => Signal.const(false)
  }

  /**
    * From here on, we don't need to worry about empty signals, since the call activity and notifications should be closed if there is no active call.
    */
  val v2Service = zms.map(_.voice)
  val convId = convIdOpt.collect { case Some(c) => c }

  val v2ServiceAndCurrentConvId = for {
    vcs <- v2Service
    cId <- convId
  } yield (vcs, cId)

  val v3ServiceAndCurrentConvId = for {
    svc <- v3Service
    c <- convId
  } yield (svc, c)

  //Note, we can't rely on the channels from ongoingAndTopIncoming directly, as they only update the presence of a channel, not their internal state
  val currentChannel = v2ServiceAndCurrentConvId.flatMap { case (vcs, id) => vcs.voiceChannelSignal(id) }

  val callState = callStateOpt.collect { case Some(s) => s }

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
    case true => v3Call.map(_.isVideoCall)
    case _ => currentChannel.map(_.video.isVideoCall)
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

