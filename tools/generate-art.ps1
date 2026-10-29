# Generates Duel Dojo's original artwork (MIT, same as the mod):
#   src/main/resources/assets/dueldojo/textures/entity/dojo_bot.png  (64x64 player-layout skin)
#   src/main/resources/assets/dueldojo/icon.png                      (128x128 mod icon)
# Run from the project root:  powershell -ExecutionPolicy Bypass -File tools\generate-art.ps1
Add-Type -AssemblyName System.Drawing
$root = Split-Path $PSScriptRoot -Parent
$C = [System.Drawing.Color]

function Fill($g, $color, $x, $y, $w, $h) {
  $b = New-Object System.Drawing.SolidBrush $color; $g.FillRectangle($b, $x, $y, $w, $h); $b.Dispose()
}

# ---------- skin (standard 64x64 player layout, outer layers left transparent) ----------
$skin = New-Object System.Drawing.Bitmap 64, 64
$g = [System.Drawing.Graphics]::FromImage($skin)
$g.Clear($C::Transparent)
$skinTone = $C::FromArgb(222, 172, 132); $hair = $C::FromArgb(40, 32, 30); $gi = $C::FromArgb(236, 236, 230)
$giShade = $C::FromArgb(205, 205, 198); $belt = $C::FromArgb(25, 25, 28); $band = $C::FromArgb(196, 40, 46)
$pants = $C::FromArgb(228, 228, 222); $feet = $C::FromArgb(70, 52, 40)

# head: faces at (top 8,0) (bottom 16,0) (right 0,8) (front 8,8) (left 16,8) (back 24,8), each 8x8
Fill $g $skinTone 0 0 32 16
Fill $g $hair 8 0 8 8                       # top
Fill $g $hair 0 8 32 2                      # hairline all around
Fill $g $hair 24 8 8 6                      # back of head
Fill $g $band 0 10 32 1                     # headband all around
Fill $g $band 26 11 2 2                     # knot at the back
Fill $g $C::White 9 12 2 1; Fill $g $C::White 13 12 2 1     # eyes
Fill $g $hair 10 12 1 1;    Fill $g $hair 13 12 1 1
Fill $g $C::FromArgb(150, 90, 70) 11 14 2 1                 # mouth

# body: (front 20,20) (back 32,20) (right 16,20) (left 28,20) 8x12/4x12, top 20,16 bottom 28,16
Fill $g $gi 16 16 24 16
Fill $g $giShade 23 20 2 6                  # gi overlap (V)
Fill $g $skinTone 23 20 2 2                 # neck opening
Fill $g $belt 16 27 24 2                    # black belt
Fill $g $belt 23 29 2 2                     # belt knot tails

# right arm (40,16 region) and left arm (32,48 region): sleeves + skin hands
Fill $g $gi 40 16 16 16;  Fill $g $skinTone 40 28 16 4;  Fill $g $skinTone 48 16 4 4
Fill $g $gi 32 48 16 16;  Fill $g $skinTone 32 60 16 4;  Fill $g $skinTone 40 48 4 4
Fill $g $giShade 44 20 4 1; Fill $g $giShade 36 52 4 1      # sleeve seams

# right leg (0,16 region) and left leg (16,48 region): pants + bare feet
Fill $g $pants 0 16 16 16;  Fill $g $feet 0 30 16 2;  Fill $g $feet 8 16 4 4
Fill $g $pants 16 48 16 16; Fill $g $feet 16 62 16 2; Fill $g $feet 24 48 4 4
$g.Dispose()
$skinPath = Join-Path $root 'src\main\resources\assets\dueldojo\textures\entity\dojo_bot.png'
$skin.Save($skinPath, [System.Drawing.Imaging.ImageFormat]::Png); $skin.Dispose()

# ---------- icon: two crossed swords on a dark dojo-red tile ----------
$icon = New-Object System.Drawing.Bitmap 128, 128
$g = [System.Drawing.Graphics]::FromImage($icon)
$g.SmoothingMode = 'AntiAlias'
$rect = New-Object System.Drawing.Rectangle 0, 0, 128, 128
$bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, $C::FromArgb(120, 24, 30), $C::FromArgb(40, 10, 16), 90
$g.FillRectangle($bg, $rect); $bg.Dispose()
function Sword($g, $angle) {
  $state = $g.Save()
  $g.TranslateTransform(64, 64); $g.RotateTransform($angle)
  Fill $g $C::FromArgb(230, 236, 242) -5 -52 10 70          # blade
  Fill $g $C::FromArgb(170, 180, 192) 0 -52 5 70            # blade shading
  $tip = New-Object System.Drawing.SolidBrush $C::FromArgb(230, 236, 242)
  $g.FillPolygon($tip, [System.Drawing.Point[]]@((New-Object System.Drawing.Point -5, -52), (New-Object System.Drawing.Point 5, -52), (New-Object System.Drawing.Point 0, -62))); $tip.Dispose()
  Fill $g $C::FromArgb(212, 168, 60) -16 18 32 6            # guard
  Fill $g $C::FromArgb(60, 40, 30) -4 24 8 22               # grip
  Fill $g $C::FromArgb(212, 168, 60) -6 46 12 6             # pommel
  $g.Restore($state)
}
Sword $g 45; Sword $g -45
$g.Dispose()
$iconPath = Join-Path $root 'src\main\resources\assets\dueldojo\icon.png'
$icon.Save($iconPath, [System.Drawing.Imaging.ImageFormat]::Png); $icon.Dispose()
"wrote $skinPath"; "wrote $iconPath"
