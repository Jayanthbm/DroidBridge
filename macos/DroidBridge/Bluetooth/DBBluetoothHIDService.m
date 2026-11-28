#import "DBBluetoothHIDService.h"
#import <CoreBluetooth/CoreBluetooth.h>
#import <IOBluetooth/IOBluetooth.h>
#import <objc/runtime.h>
#import <stdint.h>

@class DBBluetoothHIDService;
static DBBluetoothHIDService *DBGlobalHIDService;

@interface DBBluetoothHIDService (DBCBClassicPeerHandling)
- (void)handleClassicChannel:(id)channel address:(NSString *)address;
@end

@interface NSObject (DBCBL2CAPChannel)
- (instancetype)initWithPeer:(id)peer manager:(id)manager info:(NSDictionary *)info;
@end

// macOS routes incoming Classic L2CAP channels through CoreBluetooth before
// handing them to IOBluetooth. These private CBClassicPeer callbacks deliver
// the channel object directly, which avoids the broken notification bridge on
// recent macOS releases.
@interface NSObject (DBCBClassicPeerCallbacks)
- (void)setConnectL2CAPCallback:(id)callback;
- (void)setDisconnectL2CAPCallback:(id)callback;
@end

@interface IOBluetoothDevice (DBCBClassicPeer)
- (id)classicPeer;
@end

@interface IOBluetoothL2CAPChannel (DBCBClassicChannel)
+ (instancetype)channelFromCBL2CAPChannel:(id)channel;
@end

// IOBluetooth exposes these preference functions but does not declare them in
// the public headers. They are the only way for an app to make the Mac
// discoverable long enough for the phone to start the HID bond.
extern int IOBluetoothPreferenceGetDiscoverableState(void);
extern void IOBluetoothPreferenceSetDiscoverableState(int state);

// The HIDP host opens these two fixed L2CAP PSMs after SDP discovery.
static const BluetoothL2CAPPSM DBHIDControlPSM = 0x0011;
static const BluetoothL2CAPPSM DBHIDInterruptPSM = 0x0013;

// macOS 26/27 hands incoming Classic L2CAP channels to CoreBluetooth first.
// The stock handler looks for an already-registered CBL2CAPChannel and closes
// the connection when none exists, so replace it and build the channel
// ourselves before handing it to IOBluetooth.
static void (*DBOriginalHandleL2CAPChannelOpened)(id, SEL, NSDictionary *);

static void DBHandleL2CAPChannelOpened(id self, SEL _cmd, NSDictionary *info) {
    NSNumber *psmValue = info[@"kCBMsgArgPSM"];
    unsigned short psm = psmValue.unsignedShortValue;
    NSLog(@"DroidBridgeHID: swizzled handleL2CAP psm=0x%04x peer=%@ info=%@", psm, self, info);
    if (psm != DBHIDControlPSM && psm != DBHIDInterruptPSM) {
        if (DBOriginalHandleL2CAPChannelOpened) DBOriginalHandleL2CAPChannelOpened(self, _cmd, info);
        return;
    }

    Class channelClass = NSClassFromString(@"CBL2CAPChannel");
    id channel = [[channelClass alloc] initWithPeer:self
                                            manager:[self valueForKey:@"manager"]
                                               info:info];
    if (!channel) {
        NSLog(@"DroidBridgeHID: CBL2CAPChannel init failed peer=%@ manager=%@", self, [self valueForKey:@"manager"]);
        if (DBOriginalHandleL2CAPChannelOpened) DBOriginalHandleL2CAPChannelOpened(self, _cmd, info);
        return;
    }

    id serviceUUID = info[@"kCBMsgArgServiceUUID"];
    if (serviceUUID) [channel setValue:serviceUUID forKey:@"serviceUUID"];
    NSHashTable *channels = [self valueForKey:@"L2CAPChannels"];
    if (channels) [channels addObject:channel];

    // CBClassicPeer has no KVC-compliant address property. The wrapped
    // IOBluetooth channel carries the device address, so let that path resolve it.
    [DBGlobalHIDService handleClassicChannel:channel address:nil];
}

static void DBInstallClassicPeerSwizzle(void) {
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        Class peerClass = NSClassFromString(@"CBClassicPeer");
        SEL selector = NSSelectorFromString(@"handleL2CAPChannelOpened:");
        Method method = class_getInstanceMethod(peerClass, selector);
        if (!method) return;
        DBOriginalHandleL2CAPChannelOpened =
            (void (*)(id, SEL, NSDictionary *))method_getImplementation(method);
        method_setImplementation(method, (IMP)DBHandleL2CAPChannelOpened);
    });
}

// A combined keyboard, relative mouse, and consumer-control device. Report IDs
// are part of the public wire contract; keep them in sync with HIDReportMap.
static const uint8_t DBReportMouse = 1;
static const uint8_t DBReportKeyboard = 2;
static const uint8_t DBReportConsumer = 3;

static const uint8_t DBReportMap[] = {
    // Relative mouse, report 1.
    0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, DBReportMouse,
    0x09, 0x01, 0xA1, 0x00,
    0x05, 0x09, 0x19, 0x01, 0x29, 0x03,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x03, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
    0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
    0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
    0xC0, 0xC0,

    // Keyboard, report 2.
    0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, DBReportKeyboard,
    0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7,
    0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
    0x95, 0x01, 0x75, 0x08, 0x81, 0x03,
    0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65,
    0x05, 0x07, 0x19, 0x00, 0x29, 0x65, 0x81, 0x00,
    0x95, 0x05, 0x75, 0x01, 0x05, 0x08, 0x19, 0x01, 0x29, 0x05, 0x91, 0x02,
    0x95, 0x01, 0x75, 0x03, 0x91, 0x03, 0xC0,

    // Consumer-control array, report 3. 16-bit usage values let the Mac send
    // AC Back (0x0224), AC Home (0x0223), and AC Search (0x0221).
    0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, DBReportConsumer,
    0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03,
    0x75, 0x10, 0x95, 0x01, 0x81, 0x00, 0xC0,
};

static NSString *DBNormalizeAddress(NSString *address) {
    return [[address stringByReplacingOccurrencesOfString:@"-" withString:@":"] uppercaseString];
}

static NSDictionary *DBUnsigned(NSUInteger size, uint32_t value) {
    return @{
        @"DataElementType": @1,
        @"DataElementSize": @(size),
        @"DataElementValue": @(value),
    };
}

static NSDictionary *DBBoolean(BOOL value) {
    return @{
        @"DataElementType": @5,
        @"DataElementSize": @1,
        @"DataElementValue": @(value),
    };
}

static NSDictionary *DBBinaryString(NSData *value) {
    return @{
        @"DataElementType": @4,
        @"DataElementSize": @(value.length),
        @"DataElementValue": value,
    };
}

static NSUInteger DBInputReportLength(uint8_t reportID) {
    switch (reportID) {
        case DBReportMouse: return 4;
        case DBReportKeyboard: return 8;
        case DBReportConsumer: return 2;
        default: return 0;
    }
}

@interface DBBluetoothHIDService () <IOBluetoothL2CAPChannelDelegate>
@property (nonatomic, strong, nullable) IOBluetoothSDPServiceRecord *serviceRecord;
@property (nonatomic, strong) NSMutableArray<IOBluetoothUserNotification *> *channelNotifications;
@property (nonatomic, strong) NSMutableArray *classicPeers;
@property (nonatomic, strong) NSMutableArray *classicChannels;
@property (nonatomic, strong) NSMutableDictionary<NSString *, IOBluetoothL2CAPChannel *> *controlChannels;
@property (nonatomic, strong) NSMutableDictionary<NSString *, IOBluetoothL2CAPChannel *> *interruptChannels;
@property (nonatomic, strong) NSMutableSet<NSString *> *notifiedAddresses;
@property (nonatomic, strong) NSMutableSet<NSString *> *allowedAddresses;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableDictionary<NSNumber *, NSData *> *> *lastInputReports;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableArray<NSDictionary<NSString *, id> *> *> *pendingReports;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastWriteTimes;
@property (nonatomic, strong) NSMutableSet<NSString *> *flushScheduled;
@property (nonatomic, strong) NSMutableSet<NSString *> *interruptWritesInFlight;
@property (nonatomic) BOOL active;
@property (nonatomic) BOOL pairingMode;
@property (nonatomic) BOOL previousDiscoverable;
@property (nonatomic) BOOL changedDiscoverability;
@property (nonatomic, strong, nullable) NSTimer *classOfDeviceTimer;
@property (nonatomic) uint8_t protocolMode;
@property (nonatomic) uint8_t idleRate;
@property (nonatomic) IOReturn lastClassOfDeviceResult;
@property (nonatomic) BluetoothClassOfDevice lastClassOfDeviceLive;
@end

@implementation DBBluetoothHIDService

- (instancetype)init {
    self = [super init];
    if (self) {
        _channelNotifications = [NSMutableArray array];
        _classicPeers = [NSMutableArray array];
        _classicChannels = [NSMutableArray array];
        _controlChannels = [NSMutableDictionary dictionary];
        _interruptChannels = [NSMutableDictionary dictionary];
        _notifiedAddresses = [NSMutableSet set];
        _allowedAddresses = [NSMutableSet set];
        _lastInputReports = [NSMutableDictionary dictionary];
        _pendingReports = [NSMutableDictionary dictionary];
        _lastWriteTimes = [NSMutableDictionary dictionary];
        _flushScheduled = [NSMutableSet set];
        _interruptWritesInFlight = [NSMutableSet set];
        _protocolMode = 1;
        _lastClassOfDeviceResult = kIOReturnInvalid;
        _lastClassOfDeviceLive = 0xFFFFFFFF;
    }
    return self;
}

- (void)dealloc {
    [_classOfDeviceTimer invalidate];
}

- (NSString *)localDeviceName {
    return [IOBluetoothHostController defaultController].nameAsString ?: @"this Mac";
}

- (NSArray<NSString *> *)connectedAddresses {
    NSMutableArray<NSString *> *result = [NSMutableArray array];
    for (NSString *address in self.controlChannels) {
        if (self.interruptChannels[address]) [result addObject:address];
    }
    return result;
}

#pragma mark - Server lifetime

- (BOOL)publishServerWithError:(NSError **)error {
    if (self.serviceRecord && self.channelNotifications.count == 2) return YES;
    [self unpublishServer];

    NSDictionary *record = [self serviceDictionary];
    self.serviceRecord = [IOBluetoothSDPServiceRecord publishedServiceRecordWithDictionary:record];
    if (!self.serviceRecord) {
        if (error) {
            *error = [NSError errorWithDomain:@"com.jayanth.droidbridge.hid"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey: @"macOS could not publish the Bluetooth Classic HID service."}];
        }
        return NO;
    }

    // Use the same unfiltered registration the reference implementation
    // relies on. The delegate then rejects any channel that is not one of the
    // two HID PSMs, which avoids depending on PSM-filtered notification
    // delivery that macOS has made unreliable.
    IOBluetoothUserNotification *notification = [IOBluetoothL2CAPChannel
        registerForChannelOpenNotifications:self
                                   selector:@selector(channelOpened:channel:)];
    if (!notification) {
        [self unpublishServer];
        if (error) {
            *error = [NSError errorWithDomain:@"com.jayanth.droidbridge.hid"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: @"macOS could not listen for the HID L2CAP channels."}];
        }
        return NO;
    }
    [self.channelNotifications addObject:notification];
    [self installClassicPeerCallbacks];

    BluetoothSDPServiceRecordHandle handle = 0;
    [self.serviceRecord getServiceRecordHandle:&handle];
    NSLog(@"DroidBridgeHID: published service handle=0x%08x psm=0x%04x/0x%04x",
          (unsigned)handle, DBHIDControlPSM, DBHIDInterruptPSM);
    return YES;
}

- (void)installClassicPeerCallbacks {
    for (IOBluetoothDevice *device in [IOBluetoothDevice pairedDevices]) {
        id peer = [device classicPeer];
        if (!peer) continue;
        NSString *address = DBNormalizeAddress(device.addressString);
        __weak typeof(self) weakSelf = self;
        void (^connect)(id) = ^(id channel) {
            [weakSelf handleClassicChannel:channel address:address];
        };
        void (^disconnect)(id) = ^(id channel) {
            NSLog(@"DroidBridgeHID: CB channel disconnected %@", channel);
        };
        [peer setConnectL2CAPCallback:connect];
        [peer setDisconnectL2CAPCallback:disconnect];
        if (![self.classicPeers containsObject:peer]) [self.classicPeers addObject:peer];
    }
    NSLog(@"DroidBridgeHID: installed CoreBluetooth L2CAP callbacks for %lu paired peer(s)",
          (unsigned long)self.classicPeers.count);
}

- (void)handleClassicChannel:(id)channel address:(NSString *)address {
    unsigned short psm = [[channel valueForKey:@"PSM"] unsignedShortValue];
    BOOL incoming = [[channel valueForKey:@"isIncoming"] boolValue];
    NSLog(@"DroidBridgeHID: CB channel psm=0x%04x incoming=%d address=%@ channel=%@",
          psm, incoming, address, channel);
    if (!channel) return;
    // CoreBluetooth hands over a CBL2CAPChannel that closes as soon as it is
    // released. Keep it alive while IOBluetooth wraps it for the HID service.
    [self.classicChannels addObject:channel];
    IOBluetoothL2CAPChannel *wrapped = nil;
    @try {
        wrapped = [IOBluetoothL2CAPChannel channelFromCBL2CAPChannel:channel];
    } @catch (NSException *exception) {
        NSLog(@"DroidBridgeHID: wrapping CB channel threw %@", exception);
        return;
    }
    NSLog(@"DroidBridgeHID: wrapped CB channel psm=0x%04x device=%@ -> %@",
          psm, wrapped.device, wrapped);
    if (!wrapped) return;
    @try {
        [self channelOpened:nil channel:wrapped];
    } @catch (NSException *exception) {
        NSLog(@"DroidBridgeHID: channelOpened threw %@", exception);
    }
}

- (void)unpublishServer {
    for (IOBluetoothUserNotification *notification in self.channelNotifications) [notification unregister];
    [self.channelNotifications removeAllObjects];
    for (id peer in self.classicPeers) {
        [peer setConnectL2CAPCallback:nil];
        [peer setDisconnectL2CAPCallback:nil];
    }
    [self.classicPeers removeAllObjects];
    if (self.serviceRecord) {
        IOReturn result = [self.serviceRecord removeServiceRecord];
        if (result != kIOReturnSuccess) NSLog(@"DroidBridgeHID: SDP removal returned 0x%08x", result);
    }
    self.serviceRecord = nil;
}

- (BOOL)startWithPairingMode:(BOOL)pairingMode
             allowedAddresses:(NSArray<NSString *> *)allowedAddresses
                        error:(NSError **)error {
    IOBluetoothHostController *controller = [IOBluetoothHostController defaultController];
    DBGlobalHIDService = self;
    DBInstallClassicPeerSwizzle();

    if (!controller || controller.powerState == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"com.jayanth.droidbridge.hid"
                                         code:1
                                     userInfo:@{NSLocalizedDescriptionKey: @"Bluetooth is turned off."}];
        }
        return NO;
    }

    [self.allowedAddresses removeAllObjects];
    for (NSString *address in allowedAddresses) {
        [self.allowedAddresses addObject:DBNormalizeAddress(address)];
    }
    self.protocolMode = 1;
    self.idleRate = 0;

    if (![self publishServerWithError:error]) return NO;
    self.active = YES;
    [self setPairingMode:pairingMode];

    [self.classOfDeviceTimer invalidate];
    __weak typeof(self) weakSelf = self;
    self.classOfDeviceTimer = [NSTimer timerWithTimeInterval:60 repeats:YES block:^(__unused NSTimer *timer) {
        [weakSelf refreshClassOfDevice];
    }];
    [[NSRunLoop mainRunLoop] addTimer:self.classOfDeviceTimer forMode:NSRunLoopCommonModes];

    for (NSString *address in self.controlChannels.allKeys) [self notifyConnectionIfReady:address];
    if (self.availabilityHandler) self.availabilityHandler(YES, nil);
    return YES;
}

- (void)setPairingMode:(BOOL)pairingMode {
    _pairingMode = pairingMode;
    if (pairingMode && !self.changedDiscoverability) {
        self.previousDiscoverable = IOBluetoothPreferenceGetDiscoverableState() != 0;
        IOBluetoothPreferenceSetDiscoverableState(1);
        self.changedDiscoverability = YES;
    } else if (!pairingMode && self.changedDiscoverability) {
        IOBluetoothPreferenceSetDiscoverableState(self.previousDiscoverable ? 1 : 0);
        self.changedDiscoverability = NO;
    }
    [self refreshClassOfDevice];
}

- (void)stop {
    self.active = NO;
    if (DBGlobalHIDService == self) DBGlobalHIDService = nil;
    [self setPairingMode:NO];
    [self.classOfDeviceTimer invalidate];
    self.classOfDeviceTimer = nil;
    [self.notifiedAddresses removeAllObjects];
    [self.interruptWritesInFlight removeAllObjects];
    [self.pendingReports removeAllObjects];
    [self.lastWriteTimes removeAllObjects];
    [self.flushScheduled removeAllObjects];
}

- (void)shutdown {
    [self stop];
    for (IOBluetoothL2CAPChannel *channel in self.interruptChannels.allValues) [channel closeChannel];
    for (IOBluetoothL2CAPChannel *channel in self.controlChannels.allValues) [channel closeChannel];
    [self.controlChannels removeAllObjects];
    [self.interruptChannels removeAllObjects];
    [self.lastInputReports removeAllObjects];
    [self.pendingReports removeAllObjects];
    [self unpublishServer];
}

#pragma mark - Reports

- (BOOL)sendReportID:(uint8_t)reportID payload:(NSData *)payload toAddress:(NSString *)address {
    if (!self.active) return NO;
    NSString *key = DBNormalizeAddress(address);
    if (!self.interruptChannels[key]) return NO;
    if (payload.length != DBInputReportLength(reportID)) return NO;

    NSMutableDictionary<NSNumber *, NSData *> *reports = self.lastInputReports[key];
    if (!reports) {
        reports = [NSMutableDictionary dictionary];
        self.lastInputReports[key] = reports;
    }
    reports[@(reportID)] = [payload copy];

    // Queue every report in order. Key-down and key-up pairs must never be
    // coalesced, otherwise fast typing collapses into a single keystroke.
    NSMutableArray<NSDictionary<NSString *, id> *> *queue = self.pendingReports[key];
    if (!queue) {
        queue = [NSMutableArray array];
        self.pendingReports[key] = queue;
    }
    [queue addObject:@{ @"id": @(reportID), @"payload": [payload copy] }];
    if ([self.interruptWritesInFlight containsObject:key]) return YES;
    [self scheduleFlushForAddress:key];
    return YES;
}

- (BOOL)writeReportID:(uint8_t)reportID payload:(NSData *)payload toAddress:(NSString *)key {
    IOBluetoothL2CAPChannel *channel = self.interruptChannels[key];
    if (!channel) return NO;
    NSMutableData *packet = [NSMutableData dataWithCapacity:payload.length + 2];
    uint8_t prefix[2] = { 0xA1, reportID };
    [packet appendBytes:prefix length:sizeof(prefix)];
    [packet appendData:payload];
    void *refcon = (__bridge_retained void *)packet;
    self.lastWriteTimes[key] = @(CFAbsoluteTimeGetCurrent());
    [self.interruptWritesInFlight addObject:key];
    IOReturn result = [channel writeAsync:(void *)packet.bytes length:(UInt16)packet.length refcon:refcon];
    if (result != kIOReturnSuccess) {
        [self.interruptWritesInFlight removeObject:key];
        CFBridgingRelease(refcon);
        return NO;
    }
    return YES;
}

// The controller silently drops reports that are written too close together,
// so space them out while still draining the ordered queue.
- (void)scheduleFlushForAddress:(NSString *)key {
    if ([self.interruptWritesInFlight containsObject:key]) return;
    if ([self.flushScheduled containsObject:key]) return;
    NSTimeInterval now = CFAbsoluteTimeGetCurrent();
    NSTimeInterval last = [self.lastWriteTimes[key] doubleValue];
    NSTimeInterval wait = MIN(MAX(0.015 - (now - last), 0.0), 0.015);
    [self.flushScheduled addObject:key];
    __weak typeof(self) weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(wait * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) self = weakSelf;
        if (!self) return;
        [self.flushScheduled removeObject:key];
        [self flushPendingReportForAddress:key];
    });
}

- (void)flushPendingReportForAddress:(NSString *)key {
    NSMutableArray<NSDictionary<NSString *, id> *> *queue = self.pendingReports[key];
    NSDictionary<NSString *, id> *item = queue.firstObject;
    if (!item) {
        if (self.readyToSendHandler) self.readyToSendHandler(key);
        return;
    }
    [queue removeObjectAtIndex:0];
    if (![self writeReportID:[item[@"id"] unsignedCharValue]
                     payload:item[@"payload"]
                   toAddress:key]) {
        [queue removeAllObjects];
        if (self.readyToSendHandler) self.readyToSendHandler(key);
    }
}

#pragma mark - Channel lifecycle

- (void)channelOpened:(__unused IOBluetoothUserNotification *)notification
              channel:(IOBluetoothL2CAPChannel *)channel {
    NSLog(@"DroidBridgeHID: channel notification psm=0x%04x incoming=%d", channel.PSM, channel.isIncoming);
    if (channel.PSM != DBHIDControlPSM && channel.PSM != DBHIDInterruptPSM) return;
    if (!channel.isIncoming) return;

    NSString *address = DBNormalizeAddress(channel.device.addressString ?: @"");
    if (address.length == 0) return;
    NSLog(@"DroidBridgeHID: incoming channel psm=0x%04x address=%@", channel.PSM, address);

    if (![self.allowedAddresses containsObject:address]) {
        if (!self.pairingMode) {
            [channel closeChannel];
            return;
        }
        [self.allowedAddresses addObject:address];
        [self setPairingMode:NO];
        if (self.selectedDeviceHandler) self.selectedDeviceHandler(address);
    }
    [channel setDelegate:self];
}

- (void)l2capChannelOpenComplete:(IOBluetoothL2CAPChannel *)channel status:(IOReturn)error {
    NSString *address = DBNormalizeAddress(channel.device.addressString ?: @"");
    if (error != kIOReturnSuccess) {
        NSLog(@"DroidBridgeHID: channel open failed psm=0x%04x address=%@ status=0x%08x", channel.PSM, address, error);
        return;
    }
    NSLog(@"DroidBridgeHID: channel open psm=0x%04x address=%@", channel.PSM, address);
    if (channel.PSM == DBHIDControlPSM) self.controlChannels[address] = channel;
    if (channel.PSM == DBHIDInterruptPSM) self.interruptChannels[address] = channel;
    [self notifyConnectionIfReady:address];
}

- (void)l2capChannelClosed:(IOBluetoothL2CAPChannel *)channel {
    NSString *address = DBNormalizeAddress(channel.device.addressString ?: @"");
    IOBluetoothL2CAPChannel *control = self.controlChannels[address];
    IOBluetoothL2CAPChannel *interrupt = self.interruptChannels[address];
    if (control == channel) [self.controlChannels removeObjectForKey:address];
    if (interrupt == channel) [self.interruptChannels removeObjectForKey:address];
    [self.interruptWritesInFlight removeObject:address];
    [self.pendingReports removeObjectForKey:address];
    [self.lastWriteTimes removeObjectForKey:address];
    [self.flushScheduled removeObject:address];

    // A HID host needs both channels. If one side disappears, tear down the
    // other so the next connection starts from a clean pair.
    if (control && control != channel) [control closeChannel];
    if (interrupt && interrupt != channel) [interrupt closeChannel];

    if (![self.notifiedAddresses containsObject:address]) return;
    [self.notifiedAddresses removeObject:address];
    [self.lastInputReports removeObjectForKey:address];
    if (self.connectionHandler) self.connectionHandler(NO, address);
}

- (void)notifyConnectionIfReady:(NSString *)address {
    if (!self.active) return;
    if (!self.controlChannels[address] || !self.interruptChannels[address]) return;
    if ([self.notifiedAddresses containsObject:address]) return;
    [self.notifiedAddresses addObject:address];
    if (self.connectionHandler) self.connectionHandler(YES, address);
}

#pragma mark - HIDP control channel

- (void)l2capChannelData:(IOBluetoothL2CAPChannel *)channel data:(void *)dataPointer length:(size_t)dataLength {
    NSString *address = DBNormalizeAddress(channel.device.addressString ?: @"");
    if (channel != self.controlChannels[address] || dataLength == 0) return;
    const uint8_t *bytes = dataPointer;
    uint8_t transaction = bytes[0] & 0xF0;
    uint8_t parameter = bytes[0] & 0x0F;
    switch (transaction) {
        case 0x10: // HID_CONTROL
            if (parameter == 0x05) { // Virtual Cable Unplug
                [self.interruptChannels[address] closeChannel];
                [self.controlChannels[address] closeChannel];
            }
            break;
        case 0x40: // GET_REPORT
            [self answerGetReport:bytes length:dataLength channel:channel address:address];
            break;
        case 0x50: // SET_REPORT
            [self writeControlByte:0x00 channel:channel];
            break;
        case 0x60: { // GET_PROTOCOL
            uint8_t response[2] = { 0xA0, self.protocolMode };
            [self writeControlBytes:response length:2 channel:channel];
            break;
        }
        case 0x70: // SET_PROTOCOL
            self.protocolMode = parameter & 0x01;
            [self writeControlByte:0x00 channel:channel];
            break;
        case 0x80: { // GET_IDLE
            uint8_t response[2] = { 0xA0, self.idleRate };
            [self writeControlBytes:response length:2 channel:channel];
            break;
        }
        case 0x90: // SET_IDLE
            self.idleRate = parameter;
            [self writeControlByte:0x00 channel:channel];
            break;
        case 0xA0: // DATA; keyboard LEDs are intentionally ignored.
            break;
        default:
            [self writeControlByte:0x03 channel:channel];
            break;
    }
}

- (void)answerGetReport:(const uint8_t *)bytes
                 length:(size_t)length
                channel:(IOBluetoothL2CAPChannel *)channel
                address:(NSString *)address {
    uint8_t reportType = bytes[0] & 0x03;
    if (reportType != 0x01) {
        [self writeControlByte:0x03 channel:channel];
        return;
    }
    if (length < 2) {
        [self writeControlByte:0x04 channel:channel];
        return;
    }
    uint8_t reportID = bytes[1];
    NSUInteger reportLength = DBInputReportLength(reportID);
    if (reportLength == 0) {
        [self writeControlByte:0x02 channel:channel];
        return;
    }
    NSData *payload = self.lastInputReports[address][@(reportID)];
    if (payload.length != reportLength) payload = [NSMutableData dataWithLength:reportLength];
    NSMutableData *response = [NSMutableData dataWithCapacity:reportLength + 2];
    uint8_t header[2] = { 0xA1, reportID };
    [response appendBytes:header length:sizeof(header)];
    [response appendData:payload];
    [self writeControlBytes:response.bytes length:(UInt16)response.length channel:channel];
}

- (void)l2capChannelWriteComplete:(IOBluetoothL2CAPChannel *)channel
                           refcon:(void *)refcon
                           status:(IOReturn)error {
    if (refcon) CFBridgingRelease(refcon);
    NSString *address = DBNormalizeAddress(channel.device.addressString ?: @"");
    if (channel != self.interruptChannels[address]) return;
    [self.interruptWritesInFlight removeObject:address];
    if (error != kIOReturnSuccess) {
        NSLog(@"DroidBridgeHID: interrupt write failed address=%@ status=0x%08x", address, error);
    }
    [self scheduleFlushForAddress:address];
}

- (void)l2capChannelQueueSpaceAvailable:(__unused IOBluetoothL2CAPChannel *)channel {
    // The pending-report drain in l2capChannelWriteComplete is sufficient.
}

#pragma mark - Helpers

- (void)refreshClassOfDevice {
    BluetoothClassOfDevice requested = self.pairingMode ? 0x0025C0 : 0x0005C0;
    IOBluetoothHostController *controller = [IOBluetoothHostController defaultController];
    IOReturn result = [controller setClassOfDevice:requested forTimeInterval:120];
    BluetoothClassOfDevice live = controller.classOfDevice;
    if (result == self.lastClassOfDeviceResult && live == self.lastClassOfDeviceLive) return;
    self.lastClassOfDeviceResult = result;
    self.lastClassOfDeviceLive = live;
    if (result == kIOReturnSuccess && live == requested) {
        NSLog(@"DroidBridgeHID: class-of-device=0x%06x", live);
        return;
    }
    NSLog(@"DroidBridgeHID: class-of-device override returned 0x%08x; live=0x%06x", result, live);
}

- (void)writeControlByte:(uint8_t)value channel:(IOBluetoothL2CAPChannel *)channel {
    [self writeControlBytes:&value length:1 channel:channel];
}

- (void)writeControlBytes:(const void *)bytes length:(UInt16)length channel:(IOBluetoothL2CAPChannel *)channel {
    NSMutableData *packet = [NSMutableData dataWithBytes:bytes length:length];
    void *refcon = (__bridge_retained void *)packet;
    IOReturn result = [channel writeAsync:(void *)packet.bytes length:length refcon:refcon];
    if (result != kIOReturnSuccess) {
        CFBridgingRelease(refcon);
        NSLog(@"DroidBridgeHID: control write failed status=0x%08x", result);
    }
}

- (NSDictionary *)serviceDictionary {
    IOBluetoothSDPUUID *(^uuid)(uint16_t) = ^IOBluetoothSDPUUID *(uint16_t value) {
        return [IOBluetoothSDPUUID uuid16:value];
    };
    NSArray *controlProtocols = @[
        @[uuid(0x0100), DBUnsigned(2, DBHIDControlPSM)],
        @[uuid(0x0011)],
    ];
    NSArray *interruptProtocols = @[
        @[uuid(0x0100), DBUnsigned(2, DBHIDInterruptPSM)],
        @[uuid(0x0011)],
    ];
    NSData *descriptor = [NSData dataWithBytes:DBReportMap length:sizeof(DBReportMap)];
    return @{
        @"0001 - ServiceClassIDList": @[uuid(0x1124)],
        @"0004 - ProtocolDescriptorList": controlProtocols,
        @"0005 - BrowseGroupList": @[uuid(0x1002)],
        @"0006 - LanguageBaseAttributeIDList": @[
            DBUnsigned(2, 0x656E), DBUnsigned(2, 0x006A), DBUnsigned(2, 0x0100)
        ],
        @"0009 - BluetoothProfileDescriptorList": @[
            @[uuid(0x1124), DBUnsigned(2, 0x0101)]
        ],
        @"000D - AdditionalProtocolDescriptorLists": @[interruptProtocols],
        @"0100 - ServiceName": @"DroidBridge",
        @"0101 - ServiceDescription": @"Mac keyboard and mouse for Android",
        @"0102 - ProviderName": @"DroidBridge",
        @"0200 - HIDReleaseNumber": DBUnsigned(2, 0x0100),
        @"0201 - HIDParserVersion": DBUnsigned(2, 0x0111),
        @"0202 - HIDDeviceSubclass": DBUnsigned(1, 0xC0),
        @"0203 - HIDCountryCode": DBUnsigned(1, 0x00),
        @"0204 - HIDVirtualCable": DBBoolean(YES),
        @"0205 - HIDReconnectInitiate": DBBoolean(NO),
        @"0206 - HIDDescriptorList": @[
            @[DBUnsigned(1, 0x22), DBBinaryString(descriptor)]
        ],
        @"0207 - HIDLangIDBaseList": @[
            @[DBUnsigned(2, 0x0409), DBUnsigned(2, 0x0100)]
        ],
        @"0208 - HIDSDPDisable": DBBoolean(NO),
        @"0209 - HIDBatteryPower": DBBoolean(YES),
        @"020A - HIDRemoteWake": DBBoolean(YES),
        @"020B - HIDProfileVersion": DBUnsigned(2, 0x0101),
        @"020C - HIDSupervisionTimeout": DBUnsigned(2, 0x1F40),
        @"020D - HIDNormallyConnectable": DBBoolean(YES),
        @"020E - HIDBootDevice": DBBoolean(NO),
        @"020F - HIDSSRHostMaxLatency": DBUnsigned(2, 0x0640),
        @"0210 - HIDSSRHostMinTimeout": DBUnsigned(2, 0x0320),
        @"LocalAttributes": @{ @"Persistent": @NO },
    };
}

@end