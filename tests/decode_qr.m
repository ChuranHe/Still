#import <Foundation/Foundation.h>
#import <CoreImage/CoreImage.h>
#import <ImageIO/ImageIO.h>
int main(int argc,const char *argv[]) { @autoreleasepool {
    if(argc!=2)return 2;
    NSURL *url=[NSURL fileURLWithPath:[NSString stringWithUTF8String:argv[1]]];
    CGImageSourceRef source=CGImageSourceCreateWithURL((__bridge CFURLRef)url,NULL);
    if(!source)return 3;
    CGImageRef image=CGImageSourceCreateImageAtIndex(source,0,NULL);
    if(!image){CFRelease(source);return 4;}
    CIImage *input=[CIImage imageWithCGImage:image];
    CIDetector *detector=[CIDetector detectorOfType:CIDetectorTypeQRCode context:nil options:@{CIDetectorAccuracy:CIDetectorAccuracyHigh}];
    for(CIQRCodeFeature *feature in [detector featuresInImage:input])puts(feature.messageString.UTF8String);
    CGImageRelease(image);CFRelease(source);return 0;
} }
