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
package com.waz.zclient.core.stores.inappnotification;

import android.os.SystemClock;
import com.waz.api.ErrorsList;
import com.waz.api.Message;

import java.util.HashSet;
import java.util.Set;

public abstract class InAppNotificationStore implements IInAppNotificationStore {
    protected Set<InAppNotificationStoreObserver> inAppNotificationObservers = new HashSet<>();

    @Override
    public void addInAppNotificationObserver(InAppNotificationStoreObserver messageListener) {
        inAppNotificationObservers.add(messageListener);
    }

    @Override
    public void removeInAppNotificationObserver(InAppNotificationStoreObserver messageListener) {
        inAppNotificationObservers.remove(messageListener);
    }

    protected void notifyIncomingMessageObservers(Message message) {
        for (InAppNotificationStoreObserver observer : inAppNotificationObservers) {
            observer.onIncomingMessage(message);
        }
    }

    protected void notifyIncomingKnock(Message knock) {
        for (InAppNotificationStoreObserver observer : inAppNotificationObservers) {
            observer.onIncomingKnock(new KnockingEvent(knock.getUser(),
                                                       knock.getConversationId(),
                                                       SystemClock.uptimeMillis() + 50));
        }
    }

    protected void notifySyncErrorObservers(ErrorsList.ErrorDescription error) {
        for (InAppNotificationStoreObserver observer : inAppNotificationObservers) {
            observer.onSyncError(error);
        }
    }
}
