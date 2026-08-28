-- Halo device-side runtime (v2)
-- Supports HRP display, microphone, speaker, camera, battery, and input events.

local CLEAR_DISPLAY = 0x10
local PLAIN_TEXT = 0x11
local CAPTURE_PHOTO = 0x20
local MICROPHONE_START = 0x30
local MICROPHONE_STOP = 0x31
local SPEAKER_START = 0x40
local SPEAKER_STOP = 0x41
local AUDIO_CHUNK = 0x05
local AUDIO_FINAL = 0x06
local PHOTO_JPEG = 0x07
local PHOTO_FINAL = 0x08
local HRP_CODE = 0x60
local TAP_CODE = 0x09
local BUTTON_CODE = 0x0B
local BATTERY_CODE = 0x72
local STATUS_CODE = 0x70
local ERROR_CODE = 0x71
local MAX_DATA_BYTES = 32768

local QUALITIES = { 'VERY_LOW', 'LOW', 'MEDIUM', 'HIGH', 'VERY_HIGH' }

-- Minimal local equivalent of the official data.lua framing.
local pending = {}
local completed = {}
local completed_count = 0

local function send_event(code, payload)
    pcall(frame.bluetooth.send, string.char(code) .. (payload or ''))
end

local function receive_data(packet)
    if packet == nil or #packet < 1 then return end
    local flag = string.byte(packet, 1)
    local item = pending[flag]
    if item == nil then
        item = { size = 0, received = 0, chunks = {} }
        pending[flag] = item
    end
    if item.received == 0 then
        if #packet < 3 then
            pending[flag] = nil
            send_event(ERROR_CODE, 'invalid first packet')
            return
        end
        item.size = string.byte(packet, 2) << 8 | string.byte(packet, 3)
        if item.size > MAX_DATA_BYTES then
            pending[flag] = nil
            send_event(ERROR_CODE, 'message exceeds runtime limit')
            return
        end
        item.chunks[1] = string.sub(packet, 4)
        item.received = #packet - 3
    else
        item.chunks[#item.chunks + 1] = string.sub(packet, 2)
        item.received = item.received + #packet - 1
    end
    if item.received == item.size then
        completed_count = completed_count + 1
        completed[completed_count] = { flag, table.concat(item.chunks) }
        pending[flag] = nil
    elseif item.received > item.size then
        pending[flag] = nil
        send_event(ERROR_CODE, 'message length overflow')
        return
    end
    pcall(frame.bluetooth.send, '\x01\x00\x00')
end

local function process_raw_items()
    local items = completed
    completed = {}
    completed_count = 0
    collectgarbage('collect')
    return items
end

frame.bluetooth.receive_callback(receive_data)

local sprites = {}
local font_id = 0
local font_size = 8
local font_scale = 1

local function u16(s, p)
    return string.byte(s, p) << 8 | string.byte(s, p + 1)
end

local function color(s, p)
    return string.byte(s, p) << 16 | string.byte(s, p + 1) << 8 | string.byte(s, p + 2)
end

local function require_len(payload, position, needed)
    if position + needed - 1 > #payload then
        error('truncated command')
    end
end

-- HRP executor (kept from v1, 0-indexed coordinates converted to 1-indexed Lua API).
local function parse_sprite(payload)
    require_len(payload, 1, 9)
    local id = u16(payload, 1)
    local raw = string.sub(payload, 3)
    local width = u16(raw, 1)
    local height = u16(raw, 3)
    local compressed = string.byte(raw, 5) > 0
    local bpp = string.byte(raw, 6)
    local num_colors = string.byte(raw, 7)
    if compressed then error('compressed HRP sprites are not enabled') end
    if bpp ~= 1 and bpp ~= 2 and bpp ~= 4 then error('invalid HRP sprite bpp') end
    local palette_start = 8
    local palette_len = num_colors * 3
    require_len(raw, palette_start, palette_len)
    local pixels = string.sub(raw, palette_start + palette_len)
    sprites[id] = {
        width = width,
        height = height,
        bpp = bpp,
        num_colors = num_colors,
        palette_data = string.sub(raw, palette_start, palette_start + palette_len - 1),
        pixel_data = pixels
    }
end

local function execute_hrp(payload)
    if #payload > MAX_DATA_BYTES then error('HRP frame exceeds runtime limit') end
    if string.sub(payload, 1, 4) ~= 'HRP1' or string.byte(payload, 5) ~= 0 then
        error('invalid HRP header')
    end
    local count = u16(payload, 6)
    local position = 8
    for _ = 1, count do
        require_len(payload, position, 3)
        local opcode = string.byte(payload, position)
        local length = u16(payload, position + 1)
        position = position + 3
        require_len(payload, position, length)
        local command = string.sub(payload, position, position + length - 1)
        position = position + length

        if opcode == 0x01 then
            require_len(command, 1, 3)
            frame.display.clear(color(command, 1))
        elseif opcode == 0x02 then
            require_len(command, 1, 1)
            frame.display.brightness(string.byte(command, 1))
        elseif opcode == 0x03 then
            require_len(command, 1, 7)
            frame.display.set_pixel(u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x04 then
            require_len(command, 1, 11)
            frame.display.line(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5) + 1, u16(command, 7) + 1, color(command, 9))
        elseif opcode == 0x05 then
            require_len(command, 1, 12)
            frame.display.rect(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), u16(command, 7), color(command, 9), string.byte(command, 12) ~= 0)
        elseif opcode == 0x06 then
            require_len(command, 1, 10)
            frame.display.circle(u16(command, 1) + 1, u16(command, 3) + 1, u16(command, 5), color(command, 7), string.byte(command, 10) ~= 0)
        elseif opcode == 0x07 then
            require_len(command, 1, 4)
            local points = string.byte(command, 1)
            if points > 64 then error('too many polygon points') end
            local expected = 1 + points * 4 + 3
            require_len(command, 1, expected)
            local coords = {}
            for i = 0, points - 1 do
                coords[i * 2 + 1] = u16(command, 2 + i * 4) + 1
                coords[i * 2 + 2] = u16(command, 4 + i * 4) + 1
            end
            frame.display.polygon(coords, color(command, 2 + points * 4))
        elseif opcode == 0x08 then
            require_len(command, 1, 3)
            font_id = string.byte(command, 1)
            font_size = string.byte(command, 2)
            font_scale = string.byte(command, 3)
            frame.display.set_font(font_id, font_size, font_scale)
        elseif opcode == 0x09 then
            require_len(command, 1, 9)
            local text_len = u16(command, 8)
            require_len(command, 1, 9 + text_len)
            frame.display.text(string.sub(command, 10, 9 + text_len), u16(command, 1) + 1, u16(command, 3) + 1, color(command, 5))
        elseif opcode == 0x0A then
            parse_sprite(command)
        elseif opcode == 0x0B then
            require_len(command, 1, 7)
            local sprite = sprites[u16(command, 1)]
            if sprite == nil then error('sprite resource not found') end
            frame.display.bitmap(u16(command, 3) + 1, u16(command, 5) + 1, sprite.width, sprite.height, 2 ^ sprite.bpp, string.byte(command, 7), sprite.pixel_data, { palette_data = sprite.palette_data })
        elseif opcode == 0x0C then
            require_len(command, 1, 2)
            sprites[u16(command, 1)] = nil
            collectgarbage('collect')
        elseif opcode == 0x0D then
            -- dirty region hint, no-op
        elseif opcode == 0x0E then
            -- show() is no-op on Halo
        elseif opcode == 0x0F then
            -- feature negotiation, no-op in v2
        else
            error('unknown HRP opcode ' .. tostring(opcode))
        end
    end
    if position ~= #payload + 1 then
        error('trailing HRP bytes')
    end
    collectgarbage('collect')
end

-- State for streaming features.
local micStreaming = false
local micConfig = {}
local speakerStreaming = false
local photoPending = nil

-- Display helpers.
local COLORS = {
    0x000000, 0xFFFFFF, 0x808080, 0xFF0000, 0xFFC0CB, 0x654321, 0x964B00,
    0xFFA500, 0xFFFF00, 0x006400, 0x00FF00, 0x90EE90, 0x191970, 0x0000CD,
    0x87CEEB, 0xF0F8FF
}

local function draw_plain_text(payload)
    if #payload < 6 then return end
    local x = u16(payload, 1)
    local y = u16(payload, 3)
    local palette = string.byte(payload, 5) % 16 + 1
    local c = COLORS[palette]
    local spacing = string.byte(payload, 6)
    local text = string.sub(payload, 7)
    local i = 0
    for line in text:gmatch('([^\r\n]*)\r?\n?') do
        if line ~= '' then
            frame.display.text(line, x, y + i * spacing, c)
            i = i + 1
        end
    end
end

-- Audio helpers.
local function mic_chunk_size()
    local max = frame.bluetooth.max_length() - 1
    if max % 2 == 1 then max = max - 1 end
    return max
end

local function send_mic_chunks()
    if not micStreaming then return end
    local max = mic_chunk_size()
    for _ = 1, 10 do
        local data = frame.microphone.read(max)
        if data == nil then
            send_event(AUDIO_FINAL, '')
            micStreaming = false
            break
        end
        if data ~= '' then
            local ok, err = pcall(frame.bluetooth.send, string.char(AUDIO_CHUNK) .. data)
            if not ok then
                print('mic send error: ' .. tostring(err))
                send_event(ERROR_CODE, 'mic send failed')
                micStreaming = false
                break
            end
        end
    end
end

-- Camera helpers.
local function photo_chunk_size()
    return frame.bluetooth.max_length() - 1
end

local function send_photo()
    if not photoPending then return end
    if not frame.camera.image_ready() then return end
    local max = photo_chunk_size()
    while true do
        local chunk = frame.camera.read(max)
        if chunk == nil then
            send_event(PHOTO_FINAL, '')
            photoPending = nil
            collectgarbage('collect')
            break
        end
        if chunk ~= '' then
            local ok, err = pcall(frame.bluetooth.send, string.char(PHOTO_JPEG) .. chunk)
            if not ok then
                print('photo send error: ' .. tostring(err))
                send_event(ERROR_CODE, 'photo send failed')
                photoPending = nil
                break
            end
        end
    end
end

-- Battery helper.
local function send_battery()
    local level = frame.battery_level()
    local voltage = frame.battery_voltage()
    local charging = frame.battery_charging() and 1 or 0
    local payload = string.char(level) .. string.char(voltage >> 8) .. string.char(voltage & 0xFF) .. string.char(charging)
    send_event(BATTERY_CODE, payload)
end

-- Message dispatch.
local function handle_message(code, payload)
    if code == HRP_CODE then
        local ok, err = pcall(execute_hrp, payload)
        if not ok then
            send_event(ERROR_CODE, tostring(err))
            print(err)
        end
    elseif code == CLEAR_DISPLAY then
        frame.display.clear()
    elseif code == PLAIN_TEXT then
        pcall(draw_plain_text, payload)
    elseif code == MICROPHONE_START then
        micConfig = {}
        if #payload >= 3 then
            local gain = string.byte(payload, 1)
            if gain > 20 then gain = 20 end
            micConfig.gain = gain
            micConfig.aec = string.byte(payload, 2) ~= 0
            micConfig.voice = string.byte(payload, 3) ~= 0
        else
            micConfig.gain = 10
            micConfig.aec = true
            micConfig.voice = false
        end
        local ok, err = pcall(frame.microphone.start, {
            encoder = 'pcm',
            sample_rate = 16000,
            bit_depth = 16,
            channels = 1,
            gain = micConfig.gain,
            aec = micConfig.aec,
            voice = micConfig.voice,
            duration = 1000,
        })
        if ok then
            micStreaming = true
        else
            print('mic start error: ' .. tostring(err))
            send_event(ERROR_CODE, 'mic start failed')
        end
    elseif code == MICROPHONE_STOP then
        micStreaming = false
        pcall(frame.microphone.stop)
    elseif code == SPEAKER_START then
        local config = { encoder = 'pcm', sample_rate = 16000, bit_depth = 16, channels = 1, volume = 80, duration = 1000 }
        if #payload >= 1 then config.encoder = (string.byte(payload, 1) == 1) and 'lc3' or 'pcm' end
        if #payload >= 3 then config.sample_rate = u16(payload, 2) end
        if #payload >= 4 then config.bit_depth = string.byte(payload, 4) end
        if #payload >= 5 then config.channels = string.byte(payload, 5) end
        if #payload >= 6 then config.volume = string.byte(payload, 6) end
        local ok, err = pcall(frame.speaker.start, config)
        if not ok then
            print('speaker start error: ' .. tostring(err))
            send_event(ERROR_CODE, 'speaker start failed')
        else
            speakerStreaming = true
        end
    elseif code == SPEAKER_STOP then
        speakerStreaming = false
        pcall(frame.speaker.stop)
    elseif code == CAPTURE_PHOTO then
        if photoPending then return end
        local quality_index = 4
        local half_res = 256
        local pan_shifted = 140
        local raw = false
        if #payload >= 6 then
            quality_index = string.byte(payload, 1)
            half_res = u16(payload, 2)
            pan_shifted = u16(payload, 4)
            raw = string.byte(payload, 6) ~= 0
        end
        if quality_index < 1 then quality_index = 1 elseif quality_index > 5 then quality_index = 5 end
        local quality = QUALITIES[quality_index]
        local resolution = half_res * 2
        local pan = pan_shifted - 140
        local cfg = { resolution = resolution, quality = quality, pan = pan }
        if raw then cfg.raw = true end
        local ok, err = pcall(frame.camera.capture, cfg)
        if ok then
            photoPending = { resolution = resolution, quality = quality, raw = raw }
        else
            print('camera capture error: ' .. tostring(err))
            send_event(ERROR_CODE, 'camera capture failed')
        end
    elseif code == BATTERY_CODE then
        pcall(send_battery)
    else
        -- ignore unknown
    end
end

-- Input callbacks.
frame.button.single(function() send_event(BUTTON_CODE, string.char(1)) end)
frame.button.double(function() send_event(BUTTON_CODE, string.char(2)) end)
frame.button.long(function() send_event(BUTTON_CODE, string.char(3)) end)
frame.imu.tap_callback(function(kind)
    local codes = { single = 1, double = 2, triple = 3 }
    send_event(TAP_CODE, string.char(codes[kind] or 1))
end)
frame.display.power_save(false)
send_event(STATUS_CODE, 'HRP1;primitives,sprites,click,tap,mic,speaker,photo,battery')
print('Halo Engine v2 ready')

while true do
    local ok, err = pcall(function()
        local items = process_raw_items()
        for i = 1, #items do
            handle_message(items[i][1], items[i][2])
        end
        if micStreaming then
            send_mic_chunks()
        end
        if photoPending then
            send_photo()
        end
        frame.sleep(0.001)
    end)
    if not ok then
        send_event(ERROR_CODE, tostring(err))
        print(err)
    end
end
