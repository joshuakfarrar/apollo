USE [webapp]
GO

SET ANSI_NULLS ON
GO

SET QUOTED_IDENTIFIER ON
GO

CREATE TABLE [dbo].[resets](
	[user_id] [uniqueidentifier] NOT NULL,
	[code] [nchar](32) NOT NULL,
	[created_at] [datetimeoffset](7) NOT NULL
) ON [PRIMARY]
GO

ALTER TABLE [dbo].[resets]  WITH CHECK ADD FOREIGN KEY([user_id])
REFERENCES [dbo].[users] ([id])
GO