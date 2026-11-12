# veuxtorch

this is a proof-of-concept android app and native c driver that demonstrates how to gain per-bit control of flashlight brightness for xiaomi redmi note 11 pro 5g and poco x4 pro 5g devices (codenames veux and peux). the stock implementations of android and oem software put a restriction on the rear flashlight to be on or off only at the factory set current. this project breaks these constraints and provides access to the entire set of discrete steps of brightness that the physical flash driver supports, by using the qualcomm camera control interface.

## hardware architecture and driver operation

the device platform is powered by the qualcomm sm6375 processor, which is paired with a kinetic ktd2691 led flash driver as the rear flash. instead of routing commands through the high-level android camera services, it communicates to the kernel through video for linux 2 (v4l2) device nodes. camera flash subdevice node, /dev/v4l-subdev15, accepts random i2c writes, along with specific power state sequences. qualcomm camera request manager exposed at /dev/video0 manages active hardware sessions and coordinates shared dma command buffers. before any changes to the hardware brightness registers take effect, the hardware needs to be powered on by pulling the tlmm pin 134 high by a verified 56 byte power buffer using a custom gpio sequence type 11. it can then continuously adjust the current level by writing to register 0x04. the hardware is designed to support up to 256 different levels of illumination.

## challenges with dealing directly with qualcomm camera infrastructure

one main challenge when dealing directly with qualcomm camera infrastructure is the strict single-client exclusivity with the camera request manager kernel driver. the kernel driver allows only one active process to hold /dev/video0 open at a time. as a result, the camera hardware abstraction layer daemon maintains an open file descriptor to /dev/video0 from the time the system boots. to be compatible with different operating systems, including different versions of miui, hyperos, lineageos, aosp, etc., it does not hardcode vendor service names. the root service detects the name of the camera service provider that is managing the hardware at run time by inspecting the running system properties. the application pauses the camera service provider before opening the devices. this allows the flashlight driver to claim the hardware. it automatically restarts the service provider on exit.

## safety constraints and operational safety

flash trigger hardware is designed to work within the confines of the camera hardware framework and therefore, to account for safety, other constraints need to be added when used outside this framework. in particular, the native c implementation puts a hard cap on the upper limit of the register writes and sets it to 0x2c, so as to not overdrive the led beyond the thermal limits set by the manufacturer. additionally, an internal watchdog, running on a monotonic system clock, keeps a check on the activity and allows a timeout of 5 seconds to deliver a keepalive to the host user interface. in case of timeout or unexpected hardware errors, the driver issues a command to shutoff the camera trigger unit, releases all the resources, and resets the camera request manager to allow normal use of the system camera.

## disclaimer

this project is for research purposes only. while it successfully demonstrates low-level control of the mobile camera hardware in qualcomm-based mobile platforms, driving mobile camera hardware beyond framework constraints set by the hardware vendor can potentially damage the flash components and the software needs to be handled with appropriate caution.
