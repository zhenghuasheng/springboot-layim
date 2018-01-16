package com.fyp.layim.im.common;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tio.core.Aio;
import org.tio.core.ChannelContext;
import org.tio.core.exception.AioDecodeException;
import org.tio.core.utils.ByteBufferUtils;
import org.tio.websocket.common.Opcode;
import org.tio.websocket.common.WsRequest;
import org.tio.websocket.common.WsSessionContext;

/**
 * @author fyp
 * @crate 2017/11/19 21:20
 * @project SpringBootLayIM
 * 源代码为  WsServerDecoder，由于要区分消息类型，Packet中增加type属性，所以源代码贴过来稍作修改
 */
public class LayimServerDecoder {
    private static Logger log = LoggerFactory.getLogger(LayimServerDecoder.class);

    public static WsRequest decode(ByteBuffer buf, ChannelContext channelContext) throws AioDecodeException {
        WsSessionContext imSessionContext = (WsSessionContext) channelContext.getAttribute();
        List<byte[]> lastParts = imSessionContext.getLastParts();

        //第一阶段解析
        int initPosition = buf.position();
        int readableLength = buf.limit() - initPosition;

        int headLength = WsRequest.MINIMUM_HEADER_LENGTH;

        if (readableLength < headLength) {
            return null;
        }

        byte first = buf.get();

        boolean fin = (first & 0x80) > 0;

        /*
        @SuppressWarnings("unused")
        int rsv = (first & 0x70) >>> 4;//得到5、6、7 为01110000 然后右移四位为00000111
        */
        //后四位为opCode 00001111
        byte opCodeByte = (byte) (first & 0x0F);

        Opcode opcode = Opcode.valueOf(opCodeByte);
        if (opcode == Opcode.CLOSE) {
        }
        if (!fin) {
            log.error("{} 暂时不支持fin为false的请求", channelContext);
            Aio.remove(channelContext, "暂时不支持fin为false的请求");
            return null;
        } else {
            imSessionContext.setLastParts(null);
        }
        //向后读取一个字节
        byte second = buf.get();
        //用于标识PayloadData是否经过掩码处理。如果是1，Masking-key域的数据即是掩码密钥，用于解码PayloadData。客户端发出的数据帧需要进行掩码处理，所以此位是1。
        boolean hasMask = (second & 0xFF) >> 7 == 1;

        // Client data must be masked
        //第9为为mask,必须为1
        if (!hasMask) {
            //throw new AioDecodeException("websocket client data must be masked");
        } else {
            headLength += 4;
        }
        int payloadLength = second & 0x7F; //读取后7位  Payload legth，如果<126则payloadLength

        byte[] mask = null;
        //为126读2个字节，后两个字节为payloadLength
        Integer payloadLength4 = 126;
        //127读8个字节,后8个字节为payloadLength
        Integer payloadLength8 = 127;
        if (payloadLength == payloadLength4) {

            headLength += 2;
            if (readableLength < headLength) {
                return null;
            }
            payloadLength = ByteBufferUtils.readUB2WithBigEdian(buf);
            log.info("{} payloadLengthFlag: 126，payloadLength {}", channelContext, payloadLength);

        } else if (payloadLength == payloadLength8) {

            headLength += 8;
            if (readableLength < headLength) {
                return null;
            }

            payloadLength = (int) buf.getLong();
            log.info("{} payloadLengthFlag: 127，payloadLength {}", channelContext, payloadLength);
        }

        if (payloadLength < 0 || payloadLength > WsRequest.MAX_BODY_LENGTH) {
            throw new AioDecodeException("body length(" + payloadLength + ") is not right");
        }

        if (readableLength < headLength + payloadLength) {
            return null;
        }

        if (hasMask) {
            mask = ByteBufferUtils.readBytes(buf, 4);
        }

        //第二阶段解析
        WsRequest layimPacket = new WsRequest();
        layimPacket.setWsEof(fin);
        layimPacket.setWsHasMask(hasMask);
        layimPacket.setWsMask(mask);
        layimPacket.setWsOpcode(opcode);
        layimPacket.setWsBodyLength(payloadLength);

        if (payloadLength == 0) {
            return layimPacket;
        }

        byte[] array = ByteBufferUtils.readBytes(buf, payloadLength);
        if (hasMask) {
            for (int i = 0; i < array.length; i++) {
                array[i] = (byte) (array[i] ^ mask[i % 4]);
            }
        }

        if (!fin) {
            log.error("payloadLength {}, lastParts size {}, array length {}", payloadLength, lastParts.size(), array.length);
            return layimPacket;
        } else {
            int allLength = array.length;
            if (lastParts != null) {
                for (byte[] part : lastParts) {
                    allLength += part.length;
                }
                byte[] allByte = new byte[allLength];

                int offset = 0;
                for (byte[] part : lastParts) {
                    System.arraycopy(part, 0, allByte, offset, part.length);
                    offset += part.length;
                }
                System.arraycopy(array, 0, allByte, offset, array.length);
                array = allByte;
            }

            layimPacket.setBody(array);
            if (opcode == Opcode.BINARY) {

            } else {
                try {
                    String text = new String(array, WsRequest.CHARSET_NAME);
                    layimPacket.setWsBodyText(text);
                } catch (UnsupportedEncodingException e) {
                    log.error(e.toString(), e);
                }
            }
        }
        return layimPacket;

    }

}