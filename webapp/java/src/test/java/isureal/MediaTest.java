package isureal;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;

class MediaTest {
    private byte[] jpeg(int width,int height)throws Exception{
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_RGB);
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)image.setRGB(x,y,((x*111+y*37)&255)<<16|((x*53+y*83)&255)<<8|((x*13+y*23)&255));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();assertTrue(ImageIO.write(image,"jpeg",bytes));return bytes.toByteArray();
    }
    @Test void validJpegAndDimensionLimits()throws Exception{
        assertTrue(Media.validJpeg(jpeg(16,16),512));
        byte[] wide=jpeg(513,1);assertFalse(Media.validJpeg(wide,512));assertTrue(Media.validJpeg(wide,4096));
        assertFalse(Media.validJpeg(new byte[]{0,1,2,3},4096));
        ByteArrayOutputStream png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",png);
        assertFalse(Media.validJpeg(png.toByteArray(),4096));
    }
    @Test void rejectsTruncatedJpegThatImageIOWouldOtherwiseRecover()throws Exception{
        byte[] jpeg=jpeg(32,32);
        for(int cut:new int[]{1,2,10,50,100})assertFalse(Media.validJpeg(Arrays.copyOf(jpeg,jpeg.length-cut),512),"cut="+cut);
        // Appending EOI does not make a prematurely ended entropy stream valid.
        byte[] corrupted=Arrays.copyOf(jpeg,jpeg.length-100);corrupted[corrupted.length-2]=(byte)0xff;corrupted[corrupted.length-1]=(byte)0xd9;
        assertFalse(Media.validJpeg(corrupted,512));
    }
    @Test void embeddedEoiDoesNotMaskTruncationAndTrailingBytesRemainAllowed()throws Exception{
        byte[] jpeg=jpeg(32,32);ByteArrayOutputStream embedded=new ByteArrayOutputStream();
        embedded.write(jpeg,0,2);embedded.write(new byte[]{(byte)0xff,(byte)0xe1,0,6,0,(byte)0xff,(byte)0xd9,0});embedded.write(jpeg,2,jpeg.length-2);
        assertTrue(Media.validJpeg(embedded.toByteArray(),512));
        assertFalse(Media.validJpeg(Arrays.copyOf(embedded.toByteArray(),embedded.size()-2),512));
        ByteArrayOutputStream trailing=new ByteArrayOutputStream();trailing.write(jpeg);trailing.write("harmless trailing bytes".getBytes(StandardCharsets.UTF_8));
        assertTrue(Media.validJpeg(trailing.toByteArray(),512));
    }
    private static byte[] multipart(String...parts){return ("--boundary\r\n"+String.join("\r\n--boundary\r\n",parts)+"\r\n--boundary--\r\n").getBytes(StandardCharsets.ISO_8859_1);}
    private static String field(String name,String value){return "Content-Disposition: form-data; name=\""+name+"\"\r\n\r\n"+value;}
    private static String file(String name,String value){return "Content-Disposition: form-data; name=\""+name+"\"; filename=\"image.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n"+value;}
    @Test void multipartKeepsSeparateFileAndTextBindings(){
        var parts=Media.multipart("multipart/form-data; boundary=\"boundary\"",multipart(field("image","not a file"),file("image","first image"),file("image","second image"),file("caption","not a caption"),field("caption","actual caption"),field("caption","ignored second caption")));
        assertEquals("first image",parts.get("image").text());assertEquals("image.jpg",parts.get("image").filename());assertEquals("actual caption",parts.get("caption").text());assertNull(parts.get("caption").filename());
        assertFalse(Media.multipart("multipart/form-data; boundary=boundary",multipart(field("image","bytes"))).containsKey("image"));
    }
    @Test void uploadBodyTypesFollowInitialBinderStatus(){
        assertTrue(Media.multipart("application/json", "{}".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(Media.multipart("application/x-www-form-urlencoded", "caption=x".getBytes(StandardCharsets.UTF_8)).isEmpty());
        assertTrue(Media.multipart("",new byte[0]).isEmpty());
        Core.ApiError error=assertThrows(Core.ApiError.class,()->Media.multipart("text/plain","foo".getBytes(StandardCharsets.UTF_8)));
        assertEquals(415,error.status);
    }
    @Test void multipartPreservesBinaryBoundaryLikeBytesAndRejectsWrongMediaTokens(){
        String data="\u0000\u00ff\r\n--boundaryNOT\r\n\u0081";
        var parts=Media.multipart("multipart/form-data; boundary=boundary",multipart(file("image",data)));
        assertArrayEquals(data.getBytes(StandardCharsets.ISO_8859_1),parts.get("image").bytes());
        assertThrows(Core.ApiError.class,()->Media.multipart("multipart/form-dataevil; boundary=boundary",multipart(file("image","x"))));
        assertFalse(Media.multipart("multipart/form-data; boundary=boundary",multipart(file("image","x").replace("form-data;","form-dataevil;"))).containsKey("image"));
    }
}
