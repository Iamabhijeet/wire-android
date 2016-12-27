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
package com.waz.zclient.controllers.notifications;

import com.waz.api.CallDirection;
import com.waz.api.CallDropped;
import com.waz.api.CallEnded;
import com.waz.api.CallEstablished;
import com.waz.api.CallJoined;
import com.waz.api.CallingEvent;
import com.waz.api.CallingEventsHandler;
import com.waz.api.IncomingRingingStarted;
import com.waz.api.LogLevel;
import com.waz.api.OutgoingRingingStarted;
import com.waz.api.ZMessagingApi;
import com.waz.zclient.controllers.tracking.ITrackingController;
import com.waz.zclient.controllers.tracking.events.calling.EndedCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.EndedVideoCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.EstablishedCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.EstablishedVideoCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.JoinedCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.JoinedVideoCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.ReceivedCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.ReceivedVideoCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.StartedCallEvent;
import com.waz.zclient.controllers.tracking.events.calling.StartedVideoCallEvent;
import com.waz.zclient.controllers.vibrator.IVibratorController;
import com.waz.zclient.core.controllers.tracking.attributes.CompletedMediaType;
import com.waz.zclient.core.controllers.tracking.attributes.RangedAttribute;
import com.waz.zclient.core.controllers.tracking.events.media.CompletedMediaActionEvent;
import com.waz.zclient.core.stores.IStoreFactory;
import timber.log.Timber;

public class CallingTrackingEventsHandler implements CallingEventsHandler {

    private static final String TAG = CallingTrackingEventsHandler.class.getName();

    private ZMessagingApi zMessagingApi;
    private ITrackingController trackingController;

    public CallingTrackingEventsHandler(ZMessagingApi zMessagingApi,
                                        IStoreFactory storeFactory,
                                        IVibratorController vibratorController,
                                        ITrackingController trackingController) {
        this.zMessagingApi = zMessagingApi;
        this.trackingController = trackingController;
    }

    @Override
    public void onCallingEvent(CallingEvent callingEvent) {
        switch (callingEvent.kind()) {
            case RINGING_STARTED:
                if (callingEvent.direction() == CallDirection.INCOMING) {
                    onIncomingRingStarted((IncomingRingingStarted) callingEvent);
                } else {
                    onOutgoingRingingStarted((OutgoingRingingStarted) callingEvent);
                }
                break;
            case RINGING_STOPPED:
                if (callingEvent.direction() == CallDirection.INCOMING) {
                    onIncomingRingingEnded();
                } else {
                    onOutgoingRingingEnded();
                }
                break;
            case CALL_JOINED:
                onCallJoined((CallJoined) callingEvent);
                break;
            case CALL_ESTABLISHED:
                onCallEstablished((CallEstablished) callingEvent);
                break;
            case CALL_ENDED:
                onCallEnded((CallEnded) callingEvent);
                break;
            case CALL_DROPPED:
                onCallDropped((CallDropped) callingEvent);
                break;
        }
    }

    private void onIncomingRingStarted(IncomingRingingStarted callingEvent) {
        logToAvs("onIncomingRingStarted");
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new ReceivedVideoCallEvent(callingEvent));
        } else {
            trackingController.tagEvent(new ReceivedCallEvent(callingEvent));
        }
    }

    private void onOutgoingRingingStarted(OutgoingRingingStarted callingEvent) {
        logToAvs("onOutgoingRingingStarted");
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new StartedVideoCallEvent(callingEvent));
            // TODO: CM-1105 Set ephemeral flag with real value
            trackingController.tagEvent(new CompletedMediaActionEvent(CompletedMediaType.VIDEO_CALL,
                                                                      callingEvent.kindOfCall().name(),
                                                                      callingEvent.isOtto(),
                                                                      false,
                                                                      ""));
        } else {
            trackingController.tagEvent(new StartedCallEvent(callingEvent));
            // TODO: CM-1105 Set ephemeral flag with real value
            trackingController.tagEvent(new CompletedMediaActionEvent(CompletedMediaType.AUDIO_CALL,
                                                                      callingEvent.kindOfCall().name(),
                                                                      callingEvent.isOtto(),
                                                                      false,
                                                                      ""));
        }
    }

    private void onIncomingRingingEnded() {
        logToAvs("onIncomingRingingEnded");
    }

    private void onOutgoingRingingEnded() {
        logToAvs("onOutgoingRingingEnded");
    }

    private void onCallJoined(CallJoined callingEvent) {
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new JoinedVideoCallEvent(callingEvent));
        } else {
            trackingController.tagEvent(new JoinedCallEvent(callingEvent));
        }
    }

    private void onCallEstablished(CallEstablished callingEvent) {
        logToAvs("onCallEstablished");
        trackingController.updateSessionAggregates(RangedAttribute.VOICE_CALLS_INITIATED);
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new EstablishedVideoCallEvent(callingEvent));
        } else {
            trackingController.tagEvent(new EstablishedCallEvent(callingEvent));
        }
    }

    private void onCallEnded(CallEnded callingEvent) {
        logToAvs("onCallEnded");
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new EndedVideoCallEvent(callingEvent));
        } else {
            trackingController.tagEvent(new EndedCallEvent(callingEvent));
        }
    }

    private void onCallDropped(CallDropped callingEvent) {
        logToAvs("onCallDropped");
        if (callingEvent.isVideoCall()) {
            trackingController.tagEvent(new EndedVideoCallEvent(callingEvent));
        } else {
            trackingController.tagEvent(new EndedCallEvent(callingEvent));
        }
    }

    private void logToAvs(String log) {
        Timber.i(log);
        try {
            zMessagingApi.getLogging().log(LogLevel.INFO, TAG, log);
        } catch (Exception e) {
            Timber.e("Unable to log to AVS");
        }
    }

    public void tearDown() {
        zMessagingApi = null;
    }
}
