/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.atomix.cluster.messaging.impl;

import io.atomix.utils.net.Address;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.ProtocolException;
import java.nio.charset.Charset;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Decoder for inbound messages.
 */
public class MessageDecoder extends ByteToMessageDecoder {

  private static final int VERSION = 1;
  private final Logger log = LoggerFactory.getLogger(getClass());

  private static final Escape ESCAPE = new Escape();
  private static final byte[] EMPTY_PAYLOAD = new byte[0];

  private static class Escape extends RuntimeException {
  }

  private static final int BYTE_SIZE = 1;
  private static final int SHORT_SIZE = 2;
  private static final int INT_SIZE = 4;
  private static final int LONG_SIZE = 8;

  private final int preamble;

  private DecoderState currentState = DecoderState.READ_SENDER_VERSION;

  private int senderVersion;
  private int senderPreamble;
  private InetAddress senderIp;
  private int senderPort;
  private Address senderAddress;

  private InternalMessage.Type type;
  private int messageId;
  private int contentLength;
  private byte[] content;
  private int subjectLength;

  MessageDecoder(int preamble) {
    this.preamble = preamble;
  }

  @Override
  @SuppressWarnings("squid:S128") // suppress switch fall through warning
  protected void decode(
      ChannelHandlerContext context,
      ByteBuf buffer,
      List<Object> out) throws Exception {

    switch (currentState) {
      case READ_SENDER_VERSION:
        if (buffer.readableBytes() < SHORT_SIZE) {
          return;
        }
        senderVersion = buffer.readShort();
        if (senderVersion != VERSION) {
          throw new ProtocolException("Unsupported protocol version: " + senderVersion);
        }
        currentState = DecoderState.READ_SENDER_IP;
      case READ_PREAMBLE:
        if (buffer.readableBytes() < INT_SIZE) {
          return;
        }
        senderPreamble = buffer.readInt();
        if (senderPreamble != preamble) {
          throw new ProtocolException("Invalid sender preamble " + senderPreamble);
        }
        currentState = DecoderState.READ_SENDER_IP;
      case READ_SENDER_IP:
        if (buffer.readableBytes() < BYTE_SIZE) {
          return;
        }
        buffer.markReaderIndex();
        int octetsLength = buffer.readByte();
        if (buffer.readableBytes() < octetsLength) {
          buffer.resetReaderIndex();
          return;
        }

        byte[] octets = new byte[octetsLength];
        buffer.readBytes(octets);
        senderIp = InetAddress.getByAddress(octets);
        currentState = DecoderState.READ_SENDER_PORT;
      case READ_SENDER_PORT:
        if (buffer.readableBytes() < INT_SIZE) {
          return;
        }
        senderPort = buffer.readInt();
        senderAddress = new Address(senderIp.getHostName(), senderPort, senderIp);
        currentState = DecoderState.READ_TYPE;
      case READ_TYPE:
        if (buffer.readableBytes() < BYTE_SIZE) {
          return;
        }
        type = InternalMessage.Type.forId(buffer.readByte());
        currentState = DecoderState.READ_MESSAGE_ID;
      case READ_MESSAGE_ID:
        try {
          messageId = readInt(buffer);
        } catch (Escape e) {
          return;
        }
        currentState = DecoderState.READ_CONTENT_LENGTH;
      case READ_CONTENT_LENGTH:
        try {
          contentLength = readInt(buffer);
        } catch (Escape e) {
          return;
        }
        currentState = DecoderState.READ_CONTENT;
      case READ_CONTENT:
        if (buffer.readableBytes() < contentLength) {
          return;
        }
        if (contentLength > 0) {
          // TODO: Perform a sanity check on the size before allocating
          content = new byte[contentLength];
          buffer.readBytes(content);
        } else {
          content = EMPTY_PAYLOAD;
        }

        switch (type) {
          case REQUEST:
            currentState = DecoderState.READ_SUBJECT_LENGTH;
            break;
          case REPLY:
            currentState = DecoderState.READ_STATUS;
            break;
          default:
            checkState(false, "Must not be here");
        }
        break;
      default:
        break;
    }

    switch (type) {
      case REQUEST:
        switch (currentState) {
          case READ_SUBJECT_LENGTH:
            if (buffer.readableBytes() < SHORT_SIZE) {
              return;
            }
            subjectLength = buffer.readShort();
            currentState = DecoderState.READ_SUBJECT;
          case READ_SUBJECT:
            if (buffer.readableBytes() < subjectLength) {
              return;
            }
            final String subject = readString(buffer, subjectLength, UTF_8);
            InternalRequest message = new InternalRequest(messageId, senderAddress, subject, content);
            out.add(message);
            currentState = DecoderState.READ_TYPE;
            break;
          default:
            break;
        }
        break;
      case REPLY:
        switch (currentState) {
          case READ_STATUS:
            if (buffer.readableBytes() < BYTE_SIZE) {
              return;
            }
            InternalReply.Status status = InternalReply.Status.forId(buffer.readByte());
            InternalReply message = new InternalReply(messageId, content, status);
            out.add(message);
            currentState = DecoderState.READ_TYPE;
            break;
          default:
            break;
        }
        break;
      default:
        checkState(false, "Must not be here");
    }
  }

  static int readInt(ByteBuf buffer) {
    if (buffer.readableBytes() < 5) {
      return readIntSlow(buffer);
    } else {
      return readIntFast(buffer);
    }
  }

  static int readIntFast(ByteBuf buffer) {
    int b = buffer.readByte();
    int result = b & 0x7F;
    if ((b & 0x80) != 0) {
      b = buffer.readByte();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        b = buffer.readByte();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          b = buffer.readByte();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            b = buffer.readByte();
            result |= (b & 0x7F) << 28;
          }
        }
      }
    }
    return result;
  }

  static int readIntSlow(ByteBuf buffer) {
    buffer.markReaderIndex();
    int b = buffer.readByte();
    int result = b & 0x7F;
    if ((b & 0x80) != 0) {
      if (buffer.readableBytes() == 0) {
        buffer.resetReaderIndex();
        throw ESCAPE;
      }
      b = buffer.readByte();
      result |= (b & 0x7F) << 7;
      if ((b & 0x80) != 0) {
        if (buffer.readableBytes() == 0) {
          buffer.resetReaderIndex();
          throw ESCAPE;
        }
        b = buffer.readByte();
        result |= (b & 0x7F) << 14;
        if ((b & 0x80) != 0) {
          if (buffer.readableBytes() == 0) {
            buffer.resetReaderIndex();
            throw ESCAPE;
          }
          b = buffer.readByte();
          result |= (b & 0x7F) << 21;
          if ((b & 0x80) != 0) {
            if (buffer.readableBytes() == 0) {
              buffer.resetReaderIndex();
              throw ESCAPE;
            }
            b = buffer.readByte();
            result |= (b & 0x7F) << 28;
          }
        }
      }
    }
    return result;
  }

  static String readString(ByteBuf buffer, int length, Charset charset) {
    if (buffer.isDirect()) {
      final String result = buffer.toString(buffer.readerIndex(), length, charset);
      buffer.skipBytes(length);
      return result;
    } else if (buffer.hasArray()) {
      final String result = new String(buffer.array(), buffer.arrayOffset() + buffer.readerIndex(), length, charset);
      buffer.skipBytes(length);
      return result;
    } else {
      final byte[] array = new byte[length];
      buffer.readBytes(array);
      return new String(array, charset);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
    try {
      log.error("Exception inside channel handling pipeline.", cause);
    } finally {
      context.close();
    }
  }
}