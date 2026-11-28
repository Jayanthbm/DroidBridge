#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// Publishes a Bluetooth Classic HID SDP record and owns the L2CAP control
/// (0x0011) and interrupt (0x0013) channels used by Android's HID host.
@interface DBBluetoothHIDService : NSObject

@property (nonatomic, copy, nullable) void (^availabilityHandler)(BOOL available, NSString * _Nullable message);
@property (nonatomic, copy, nullable) void (^connectionHandler)(BOOL connected, NSString *address);
@property (nonatomic, copy, nullable) void (^selectedDeviceHandler)(NSString *address);
@property (nonatomic, copy, nullable) void (^readyToSendHandler)(NSString *address);

@property (nonatomic, readonly) NSString *localDeviceName;
@property (nonatomic, readonly) NSArray<NSString *> *connectedAddresses;

- (BOOL)startWithPairingMode:(BOOL)pairingMode
             allowedAddresses:(NSArray<NSString *> *)allowedAddresses
                        error:(NSError **)error;
- (void)setPairingMode:(BOOL)pairingMode;
- (void)stop;
- (void)shutdown;

/// Sends a HID input report. Payload excludes the 0xA1/report-ID prefix.
- (BOOL)sendReportID:(uint8_t)reportID payload:(NSData *)payload toAddress:(NSString *)address;

@end

NS_ASSUME_NONNULL_END