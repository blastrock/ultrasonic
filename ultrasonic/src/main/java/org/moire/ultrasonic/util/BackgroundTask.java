/*
 This file is part of Subsonic.

 Subsonic is free software: you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 Subsonic is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with Subsonic.  If not, see <http://www.gnu.org/licenses/>.

 Copyright 2009 (C) Sindre Mehus
 */
package org.moire.ultrasonic.util;

import android.app.Activity;
import android.os.Handler;

/**
 * @author Sindre Mehus
 */
public abstract class BackgroundTask<T> implements ProgressListener
{
	private final Activity activity;
	private final Handler handler;

	public BackgroundTask(Activity activity)
	{
		this.activity = activity;
		handler = new Handler();
	}

	protected Activity getActivity()
	{
		return activity;
	}

	protected Handler getHandler()
	{
		return handler;
	}

	public abstract void execute();

	protected abstract T doInBackground() throws Throwable;

	protected abstract void done(T result);

	protected void error(Throwable error)
	{
		CommunicationError.handleError(error, activity);
	}

	protected String getErrorMessage(Throwable error)
	{
		return CommunicationError.getErrorMessage(error, activity);
	}

	@Override
	public abstract void updateProgress(final String message);

	@Override
	public void updateProgress(int messageId)
	{
		updateProgress(activity.getResources().getString(messageId));
	}
}