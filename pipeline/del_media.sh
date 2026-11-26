#!/system/bin/sh
# 删除媒体库里指向 2049lib 的图片记录（不删文件）
content delete --uri content://media/external/images/media --where "_data LIKE '/storage/emulated/0/2049lib/%'"
echo "images done"
content delete --uri content://media/external/video/media --where "_data LIKE '/storage/emulated/0/2049lib/%'"
echo "videos done"
content delete --uri content://media/external/audio/media --where "_data LIKE '/storage/emulated/0/2049lib/%'"
echo "audio done"
