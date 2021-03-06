/*
 * Copyright 2002-2014 iGeek, Inc.
 * All Rights Reserved
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
 
package com.igeekinc.firehose;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map.Entry;

import org.apache.log4j.Logger;

import com.igeekinc.util.async.AsyncCompletion;
import com.igeekinc.util.logging.ErrorLogMessage;
import com.igeekinc.util.logging.InfoLogMessage;

public abstract class FirehoseClient extends FirehoseBase
{
	private boolean closed = false, keepRunning = true;
	private long commandSequence;
	//protected Socket socket;
	//protected SocketChannel socketChannel;
	protected FirehoseChannel remoteChannel;
	protected HashMap<Long, CommandBlock<?>> outstandingMessages = new HashMap<Long, CommandBlock<?>>();
	protected Logger logger = Logger.getLogger(getClass());
	protected Thread responseThread;
	protected int maxOutstanding = 8;
	private SocketAddress serverAddress;
	public FirehoseClient()
	{

	}
	
	/**
	 * Sends a command to the server
	 * 
	 * @param message - The message to send
	 * @param completionHandler - Completion handler to notify when the command completes/fails
	 * @param attachment - Attachment for future notification
	 * @throws IOException
	 */
	protected <A> void sendMessage(CommandMessage message, AsyncCompletion<? extends Object, A>completionHandler, A attachment) throws IOException
	{
		sendMessage(message, null, completionHandler, attachment);
	}
	
	/**
	 * Sends a command to the server
	 * 
	 * @param message - The message to send
	 * @param bulkDataDestination - Destination for bulk data (may be null)
	 * @param completionHandler - Completion handler to notify when the command completes/fails
	 * @param attachment - Attachment for future notification
	 * @throws IOException
	 */
	protected <A>void sendMessage(CommandMessage message, ByteBuffer bulkDataDestination, AsyncCompletion<? extends Object, A>completionHandler, A attachment) throws IOException
	{
		if (closed)
			throw new IOException(getClass()+" client closed sending to "+serverAddress);
		CommandBlock<A> commandBlock;
		synchronized(outstandingMessages)
		{
			logger.debug("Sending command " + message.getCommandCode() +" sequence "+commandSequence);
			commandBlock = new CommandBlock<A>(commandSequence, message, bulkDataDestination, completionHandler, attachment);
			commandSequence++;
			while (outstandingMessages.size() > maxOutstanding)
			{
				try
				{
					outstandingMessages.wait(1000);
				} catch (InterruptedException e)
				{
					// TODO Auto-generated catch block
					Logger.getLogger(getClass()).error(new ErrorLogMessage("Caught exception"), e);
				}
			}
			outstandingMessages.put(commandBlock.getCommandSequence(), commandBlock);
		}
		sendCommandAndPayload(remoteChannel, message, commandBlock);
	}

	
	protected void responseLoop()
	{
		try
		{
			while (keepRunning)
			{
				logger.debug("Starting readCommandAndPayload");
				ReceivedPayload receivedPayload = readCommandAndPayload(remoteChannel, outstandingMessages);
				logger.debug("readCommandAndPayload completed");

				switch(receivedPayload.getCommandType())
				{
				case kCommand:
					throw new IllegalArgumentException("Received a Command packet in the client response loop");
				case kCommandReply:
					logger.debug("Received kCommandReply");
					handleCommandReply(receivedPayload);
					break;
				case kCommandFailed:
					logger.debug("Received kCommandFailed");
					handleCommandFailed(receivedPayload);
					break;
				case kUnsolicited:
					logger.debug("Received kUnsolicited");
					handleUnsolicited(receivedPayload);
					break;
				default:
					throw new IllegalArgumentException("Received unknown command type "+receivedPayload.getCommandType());
				}

			}
		}
		catch (Throwable t)
		{
			//if (!(t instanceof IOException))
				Logger.getLogger(getClass()).error(new ErrorLogMessage("Caught exception"), t);
			try
			{

				synchronized(outstandingMessages)
				{
					for (Entry<Long, CommandBlock<?>>curEntry:outstandingMessages.entrySet())
					{
						CommandBlock<?> abortBlock = curEntry.getValue();
						if (abortBlock != null && abortBlock.getFuture() != null)
						{
							abortBlock.getFuture().failed(new IOException("Remote server closed connection"), null);
						}
					}
					outstandingMessages.clear();
					outstandingMessages.notifyAll();
				}
				close();
			} catch (IOException e)
			{
				//Logger.getLogger(getClass()).error(new ErrorLogMessage("Caught exception"), e);
			}
		}
	}
	
	public void close() throws IOException
	{
		close(0);
	}
	
	public synchronized void close(long timeout) throws IOException
	{
		if (!closed)
		{
			synchronized(this)
			{
				closed = true;
			}
			long startTime = System.currentTimeMillis();

			synchronized(outstandingMessages)
			{
				while ((timeout == 0 || (System.currentTimeMillis() - startTime > timeout)) && outstandingMessages.size() > 0)
				{
					long timeToWait = timeout == 0 ? 0:System.currentTimeMillis() - startTime;
					try
					{
						outstandingMessages.wait(timeToWait);
					} catch (InterruptedException e)
					{
						Logger.getLogger(getClass()).info(new InfoLogMessage("Caught exception"), e);
					}
				}

				// Past the wait timeout

				for (Entry<Long, CommandBlock<?>>curEntry:outstandingMessages.entrySet())
				{
					CommandBlock<?> abortBlock = curEntry.getValue();
					if (abortBlock != null)
					{
						abortBlock.getFuture().failed(new IOException("Command not finished before close timeout"), null);
					}
				}
			}
			sendClose(remoteChannel, commandSequence);

			keepRunning = false;
			synchronized(remoteChannel)
			{
				remoteChannel.close();
			}
		}
	}
	@SuppressWarnings("unchecked")
	protected void handleCommandReply(ReceivedPayload receivedPayload) throws IOException
	{
		CommandBlock<?> commandBlock;
		synchronized(outstandingMessages)
		{
			logger.debug("Processing command reply for sequence "+receivedPayload.getCommandSequence());
			commandBlock = outstandingMessages.remove(receivedPayload.getCommandSequence());
			outstandingMessages.notifyAll();
		}
		if (commandBlock == null)
		{
			logger.error("Did not find commandBlock for "+receivedPayload.getCommandSequence());
			throw new IllegalArgumentException("Did not find command sequence "+receivedPayload.getCommandSequence());
		}
		Class<? extends Object>replyClass = getReturnClassForCommandCode(commandBlock.getMessage().getCommandCode());
		Object reply;
		if (!replyClass.equals(Void.class))
		{
			logger.debug("Unpacking reply info for sequence "+receivedPayload.getCommandSequence());
			reply = packer.read(receivedPayload.getPayload(), replyClass);
		}
		else
		{
			logger.debug("No reply info (Void) for sequence "+receivedPayload.getCommandSequence());
			reply = null;
		}
		AsyncCompletion<Object, Object> asyncCompletion = (AsyncCompletion<Object, Object>)commandBlock.getFuture();
		if (asyncCompletion != null)
		{
			asyncCompletion.completed(reply, commandBlock.getAttachment());
		}
		else
		{
			
		}
	}
	protected void handleCommandFailed(ReceivedPayload receivedPayload) throws IOException
	{
		CommandBlock<?> commandBlock;
		synchronized(outstandingMessages)
		{
			commandBlock = outstandingMessages.remove(receivedPayload.getCommandSequence());
			outstandingMessages.notifyAll();
		}
		if (commandBlock == null)
			throw new IllegalArgumentException("Did not find command sequence "+receivedPayload.getCommandSequence());
		Throwable failureReason = getThrowableForErrorCode(receivedPayload.getCommandCode());
		AsyncCompletion<? extends Object, ?> future = commandBlock.getFuture();
		if (future != null)	// Sometimes nobody cares
			future.failed(failureReason, null);
	}
	protected void handleUnsolicited(ReceivedPayload receivedPayload)
	{
		
	}

	public void addChannel(FirehoseChannel newTargetChannel) throws IOException
	{
		remoteChannel = newTargetChannel;
		serverAddress = remoteChannel.getSocketChannel().socket().getRemoteSocketAddress();
		remoteChannel.configureBlocking(false);
		responseThread = new Thread(new Runnable(){

			@Override
			public void run()
			{
				responseLoop();
			}
		},getClass()+" response thread "+newTargetChannel.getSocketChannel().socket().getRemoteSocketAddress());
		responseThread.start();
	}

	public void shutdown()
	{
		
	}
	
	public SocketAddress getServerAddress() throws IOException
	{
		return serverAddress;
	}
	
	public boolean isClosed()
	{
		return closed;
	}
	
	public String dump()
	{
		StringBuffer returnBuffer = new StringBuffer();
		returnBuffer.append("FirehoseClient:");

		returnBuffer.append(serverAddress.toString());
		returnBuffer.append("\n");
		
		
		returnBuffer.append("Outstanding messages:\n");
		synchronized(outstandingMessages)
		{
			Long [] keys = outstandingMessages.keySet().toArray(new Long[0]);
			for (Long curKey:keys)
			{
				CommandBlock<?>curMessage = outstandingMessages.get(curKey);
				returnBuffer.append(curMessage.toString());
				returnBuffer.append("\n");
			}
		}
		
		return returnBuffer.toString();
	}
}
