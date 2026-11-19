#pragma once
#include "config.h"

typedef struct {
    uint32_t bitsPerQuarter;
    uint32_t bitsSoFar;
    uint8_t busloadPercentage;
} BUSLOAD;

class CAN_COMMON;
class CAN_FRAME;
class CAN_FRAME_FD;

class CANManager
{
public:
    CANManager();
    void addBits(int offset, CAN_FRAME &frame);
    void addBits(int offset, CAN_FRAME_FD &frame);    
    // Installs (or reinstalls) one bus read-only. Every application CAN
    // initialization must go through this so no path can start a driver in
    // normal mode in a default build.
    void beginBus(int idx, uint32_t nominalSpeed, uint32_t dataSpeed, bool fdMode);
    void sendFrame(CAN_COMMON *bus, CAN_FRAME &frame);
    void sendFrame(CAN_COMMON *bus, CAN_FRAME_FD &frame);
    static constexpr bool canTransmit()
    {
#if POCKET_MECHANIC_ALLOW_CAN_TX
        return true;
#else
        return false;
#endif
    }
    void displayFrame(CAN_FRAME &frame, int whichBus);
    void displayFrame(CAN_FRAME_FD &frame, int whichBus);
    void loop();
    void setup();
    void setSendToConsole(bool state) { sendToConsole = state; }

private:
    BUSLOAD busLoad[NUM_BUSES];
    uint32_t busLoadTimer;
    bool sendToConsole;
};
