# NOTICE

Huawei Band Bridge is licensed under the **GNU Affero General Public License v3.0** (see `LICENSE`).

## Gadgetbridge

Large parts of the Huawei protocol implementation in `bandcore/` are ported from
[Gadgetbridge](https://codeberg.org/Freeyourgadget/Gadgetbridge) (AGPL-3.0), in particular:

- packet framing, TLV, encryption and the HiChain authentication (`proto/`, `auth/`)
- packet definitions (`proto/packets/`: DeviceConfig, FitnessData, FileDownloadService2C, FileUpload,
  DataSync, P2P, HrRriTest, Stress, Weather, MusicControl …)
- TruSleep / sequence-data parsing (`HuaweiSequenceDataFileParser`, `HuaweiTrueSleepSequenceDataParser`)
- stress calibration math (`proto/stress/`) and the RRI file parser (`HuaweiStressParser`)

Copyright of that code belongs to the Gadgetbridge contributors; the original license headers are kept
in the ported files. Changes made here: package names, a logcat/`Probe` shim instead of slf4j, and
removal of the parts that depend on Gadgetbridge's database and device model.

## Not affiliated with Huawei

"Huawei", "HUAWEI Band" and "TruSleep" are trademarks of Huawei Technologies Co., Ltd.
This project is unofficial, is not affiliated with or endorsed by Huawei, and talks to the band
through a reverse-engineered protocol that may change with any firmware update.
