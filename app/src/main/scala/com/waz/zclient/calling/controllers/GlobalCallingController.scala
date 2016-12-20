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
import _root_.com.waz.model.{ConvId, VoiceChannelData}
import _root_.com.waz.service.ZMessaging
import _root_.com.waz.utils.events.{EventContext, Signal}
import android.os.PowerManager
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.service.call.CallInfo._
import com.waz.threading.Threading
import com.waz.zclient.calling.CallingActivity
import com.waz.zclient.{Injectable, Injector, WireContext}

class GlobalCallingController(cxt: WireContext)(implicit inj: Injector) extends Injectable {

  implicit val eventContext = cxt.eventContext

  val zms = inject[Signal[Option[ZMessaging]]]
  val prefs = zms.map(_.map(_.prefs))

  val v3Service = zms.map(_.map(_.calling))

  private val screenManager = new ScreenManager

  val channels = zms.flatMap(_.fold(Signal.const[(Option[VoiceChannelData], Option[VoiceChannelData])]((None, None)))(_.voiceContent.ongoingAndTopIncomingChannel))

  val callExists = channels map {
    case (None, None) => false
    case _ => true
  }

  val voiceService = zms map (_.map(_.voice))

  val v3Call = v3Service.flatMap {
    case Some(svc) => svc.currentCall
    case None => Signal.const(NoCall)
  }

  val isV3CallActive = v3Call.map(_.state != Idle)

  //true if incoming v3 call or outgoing pref is set
  val isV3Call = prefs.zip(isV3CallActive).map {
    case (Some(prefs), v3active) => v3active || prefs.callingV3
    case _ => false
  }

  //Note, we can't rely on the channels from ongoingAndTopIncoming directly, as they only update the presence of a channel, not their internal state
  val convId = isV3Call flatMap {
    case true => v3Call.map(_.convId)
    case false => channels map {
      case (ongoing, incoming) => ongoing.orElse(incoming).map(_.id)
    }
  }

  val currentChannel: Signal[Option[VoiceChannelData]] = voiceService.zip(convId) flatMap {
    case (Some(voiceService), Some(convId)) => voiceService.voiceChannelSignal(convId) map (Some(_))
    case _ => Signal.const[Option[VoiceChannelData]](None)
  }

  val voiceServiceAndCurrentConvId = voiceService.zip(currentChannel) map {
    case (Some(vcs), vd) => vd.map(data => (vcs, data.id))
    case _ => None
  }

  val videoCall = isV3Call.flatMap {
    case true => v3Call.map(_.withVideo)
    case _ => currentChannel flatMap  {
      case (Some(data)) => Signal.const(data.video.isVideoCall)
      case _ => Signal.empty[Boolean] //empty signal to prevent 'empty' UI state on call tear-down
    }
  }

  val callState = currentChannel map {
    case Some(ch) => Some(ch.state)
    case _ => None
  }

  val activeCall = isV3Call.flatMap {
    case true => isV3CallActive
    case _ => callState.map {
      case Some(SELF_CALLING | SELF_JOINING | SELF_CONNECTED | OTHER_CALLING | OTHERS_CONNECTED) => true
      case _ => true
    }
  }

  var wasUiActiveOnCallStart = false

  val onCallStarted = activeCall.onChanged.filter(_ == true).map { _ =>
    val active = zms.flatMap(_.fold(Signal.const(false))(_.lifecycle.uiActive)).currentValue.getOrElse(false)
    wasUiActiveOnCallStart = active
    active
  }

  onCallStarted.on(Threading.Ui) { _ =>
    CallingActivity.start(cxt)
  }(EventContext.Global)

  activeCall.onChanged.filter(_ == false).on(Threading.Ui){ _ =>
    screenManager.releaseWakeLock()
  }(EventContext.Global)

  videoCall.zip(callState) {
    case (true, _) => screenManager.setStayAwake()
    case (false, Some(st)) if st == OTHER_CALLING => screenManager.setStayAwake()
    case (false, Some(st)) if st == SELF_CALLING | st == SELF_JOINING || st == SELF_CONNECTED => screenManager.setProximitySensorEnabled()
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

