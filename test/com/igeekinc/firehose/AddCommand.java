/*
 * Copyright 2002-2014 iGeek, Inc.
 * All Rights Reserved
 * @Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.@
 */
 
package com.igeekinc.firehose;

import org.msgpack.annotation.Message;

import com.igeekinc.firehose.CommandMessage;

@Message
public class AddCommand extends CommandMessage
{
	public int value1;
	public int value2;
	public AddCommand(int value1, int value2)
	{
		super(TestRemoteServer.TestCommand.kAddCommand.commandNum);
		this.value1 = value1;
		this.value2 = value2;
	}
	/**
	 * This is for msgpack - don't call this!
	 */
	public AddCommand()
	{
		
	}
}