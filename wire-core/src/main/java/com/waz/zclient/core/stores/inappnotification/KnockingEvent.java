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

import com.waz.api.User;

public class KnockingEvent implements IKnockingEvent {
    private static final int DEFAULT_DURATION = 600;
    private User user;
    private final String conversationId;
    private final long startTime;
    private boolean listHasConsumed;

    public KnockingEvent(User user, String conversationId, long startTime) {
        this.user = user;
        this.conversationId = conversationId;
        this.startTime = startTime;
    }

    public String getConversationId() {
        return conversationId;
    }

    public long getStartTime() {
        return startTime;
    }

    public int getDuration() {
        return DEFAULT_DURATION;
    }

    public String getUserName() {
        if (user != null) {
            return user.getDisplayName();
        }

        return "";
    }

    public int getColor() {
        return user.getAccent().getColor();
    }

    public void setListHasConsumed(boolean listHasConsumed) {
        this.listHasConsumed = listHasConsumed;
    }

    public boolean isListHasConsumed() {
        return listHasConsumed;
    }
}
